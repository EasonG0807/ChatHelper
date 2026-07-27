package com.example.demo.controller;

import com.example.demo.entity.ChatMessage;
import com.example.demo.entity.Conversation;
import com.example.demo.entity.DocStatus;
import com.example.demo.entity.Document;
import com.example.demo.repository.ChatMessageRepository;
import com.example.demo.repository.ConversationRepository;
import com.example.demo.repository.DocumentRepository;
import com.example.demo.service.DocAgentService;
import com.example.demo.service.DocumentService;
import com.example.demo.service.RagCachedRetrievalService;
import com.example.demo.service.RagSearchResult;
import com.example.demo.service.SpringAiService;
import com.example.agent.service.ConversationQueryRewriter;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

import java.util.List;

@Controller
@RequestMapping("/agent/doc")
@RequiredArgsConstructor
public class DocAgentController {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RagCachedRetrievalService ragCachedRetrievalService;
    private final SpringAiService springAiService;
    private final DocAgentService docAgentService;
    private final ConversationQueryRewriter queryRewriter;

    @GetMapping
    public String agentDocList(HttpSession session, Model model) {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return "redirect:/auth/login";
        }

        model.addAttribute("docs", documentService.listDocs(uid));
        return "agent_doc_list";
    }

    @GetMapping("/start")
    public String start(@RequestParam Long docId, HttpSession session, Model model) {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return "redirect:/auth/login";
        }

        Document document = documentRepository.findByIdAndUserId(docId, uid).orElse(null);
        if (document == null || document.getStatus() != DocStatus.COMPLETED) {
            return "redirect:/agent/doc";
        }

        String title = conversationTitle(docId);
        Conversation conversation = conversationRepository.findTopByUserIdAndTitle(uid, title)
                .orElseGet(() -> {
                    Conversation c = new Conversation();
                    c.setUserId(uid);
                    c.setTitle(title);
                    return conversationRepository.save(c);
                });

        model.addAttribute("conversationId", conversation.getId());
        model.addAttribute("documentId", docId);
        model.addAttribute("documentTitle", document.getTitle());
        model.addAttribute("messages", chatMessageRepository.findByConversationIdOrderByIdAsc(conversation.getId()));
        return "agent_doc";
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    @ResponseBody
    public Flux<String> ask(@RequestParam Long conversationId,
                            @RequestParam Long documentId,
                            @RequestParam(required = false, defaultValue = "") String question,
                            @RequestParam(required = false, defaultValue = "summary") String task,
                            HttpSession session) {

        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return Flux.just("【错误】用户未登录或会话已过期", "[DONE]");
        }

        boolean owned = documentRepository.existsByIdAndUserId(documentId, uid);
        if (!owned) {
            return Flux.just("【错误】无权访问该文档", "[DONE]");
        }

        String normalizedTask = docAgentService.normalizeTask(task);
        String effectiveQuestion = (question == null || question.isBlank())
                ? docAgentService.defaultQuestionForTask(normalizedTask)
                : question;

        List<ChatMessage> history = chatMessageRepository.findByConversationIdOrderByIdAsc(conversationId);
        String retrievalQuestion = queryRewriter.rewrite(
                effectiveQuestion,
                history.stream()
                        .map(message -> new ConversationQueryRewriter.Turn(message.getRole(), message.getMessage()))
                        .toList());
        RagSearchResult searchResult = ragCachedRetrievalService.search(uid, documentId, retrievalQuestion);
        String prompt = docAgentService.buildAgentPrompt(normalizedTask, effectiveQuestion, searchResult);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setMessage("【" + docAgentService.taskDisplayName(normalizedTask) + "】" + effectiveQuestion);
        chatMessageRepository.save(userMessage);

        StringBuilder finalReply = new StringBuilder();
        return Flux.create(sink -> springAiService.streamAnswer(history, prompt).subscribe(
                chunk -> {
                    finalReply.append(chunk);
                    sink.next(chunk);
                },
                err -> {
                    sink.next("【错误】" + err.getMessage());
                    sink.next("[DONE]");
                    sink.complete();
                },
                () -> {
                    String citationSummary = searchResult.buildCitationSummary();
                    finalReply.append(citationSummary);
                    sink.next(citationSummary);

                    ChatMessage ai = new ChatMessage();
                    ai.setConversationId(conversationId);
                    ai.setRole("assistant");
                    ai.setMessage(finalReply.toString());
                    chatMessageRepository.save(ai);
                    sink.next("[DONE]");
                    sink.complete();
                }
        ));
    }

    @PostMapping("/clear")
    @ResponseBody
    @Transactional
    public ResponseEntity<String> clear(@RequestParam Long documentId, HttpSession session) {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return ResponseEntity.status(401).body("用户未登录或会话已过期");
        }

        boolean owned = documentRepository.existsByIdAndUserId(documentId, uid);
        if (!owned) {
            return ResponseEntity.status(403).body("无权访问该文档");
        }

        String title = conversationTitle(documentId);
        conversationRepository.findTopByUserIdAndTitle(uid, title).ifPresent(conversation -> {
            chatMessageRepository.deleteByConversationId(conversation.getId());
            conversationRepository.deleteById(conversation.getId());
        });
        return ResponseEntity.ok("Agent 会话已清空");
    }

    private String conversationTitle(Long documentId) {
        return "Agent-Doc-" + documentId;
    }
}
