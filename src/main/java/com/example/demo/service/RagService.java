package com.example.demo.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.example.demo.repository.DocumentChunkCacheProjection;
import com.example.demo.repository.DocumentChunkProjection;
import com.example.demo.repository.DocumentChunkRepository;
import com.example.demo.repository.DocumentChunkVectorHitProjection;
import com.example.demo.search.ChunkSearchDoc;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DocumentChunkRepository chunkRepo;
    private final ElasticsearchOperations elasticsearchOperations;
    private final SpringAiService springAiService;
    private final RerankService rerankService;

    @Value("${rag.retrieval.vector-topk:20}")
    private int vectorTopK;

    @Value("${rag.retrieval.bm25-topk:20}")
    private int bm25TopK;

    @Value("${rag.retrieval.final-topk:5}")
    private int finalTopK;

    @Value("${rag.retrieval.fusion.strategy:rrf}")
    private String fusionStrategy;

    @Value("${rag.retrieval.fusion.rrf-k:60}")
    private int rrfK;

    @Value("${rag.retrieval.fusion.vector-weight:0.6}")
    private double vectorWeight;

    @Value("${rag.retrieval.fusion.bm25-weight:0.4}")
    private double bm25Weight;

    @Value("${rag.retrieval.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.retrieval.rerank.api.candidate-limit:12}")
    private int rerankCandidateLimit;

    public List<Double> embedding(String text) {
        return springAiService.embedding(text);
    }

    public List<DocumentChunkProjection> searchRelevant(Long documentId, String question) {
        return new ArrayList<>(searchRelevantWithIds(documentId, question));
    }

    public List<DocumentChunkCacheProjection> searchRelevantWithIds(Long documentId, String question) {
        return new ArrayList<>(search(documentId, question).getEvidence());
    }

    public RagSearchResult search(Long documentId, String question) {
        List<RankedChunk> vectorHits = vectorRecall(documentId, question, vectorTopK);
        List<RankedChunk> bm25Hits = bm25Recall(documentId, question, bm25TopK);

        List<RankedChunk> fused = "weighted".equalsIgnoreCase(fusionStrategy)
                ? weightedFuse(vectorHits, bm25Hits, vectorWeight, bm25Weight)
                : rrfFuse(vectorHits, bm25Hits, rrfK);

        List<RankedChunk> apiReranked = apiRerank(question, fused);
        if (apiReranked != null) {
            fused = apiReranked;
        } else if (rerankEnabled) {
            fused = lightweightRerank(fused);
        }

        List<RagChunkEvidence> evidence = buildEvidence(fused.stream()
                .limit(Math.max(1, finalTopK))
                .toList());
        return buildSearchResult(question, evidence);
    }

    private List<RankedChunk> vectorRecall(Long documentId, String question, int topK) {
        List<Double> qvec = embedding(question);
        String vectorStr = qvec.toString();
        List<DocumentChunkVectorHitProjection> rows = chunkRepo.searchSimilarWithDistance(documentId, vectorStr, topK);

        List<RankedChunk> hits = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            DocumentChunkVectorHitProjection row = rows.get(i);
            double distance = row.getDistance() == null ? 1.0 : row.getDistance();
            double score = 1.0 / (1.0 + Math.max(0.0, distance));
            hits.add(RankedChunk.vector(row.getId(), row.getText(), row.getChunkIndex(),
                    row.getPageNumber(), row.getSectionTitle(), row.getContentType(), row.getSourcePath(),
                    score, i + 1));
        }
        return hits;
    }

    private List<RankedChunk> bm25Recall(Long documentId, String question, int topK) {
        try {
            Query boolQuery = Query.of(q -> q.bool(b -> b
                    .must(m -> m.match(mm -> mm.field("text").query(question)))
                    .filter(f -> f.term(t -> t.field("documentId").value(documentId)))
            ));

            Pageable pageable = PageRequest.of(0, topK);
            NativeQuery nativeQuery = NativeQuery.builder()
                    .withQuery(boolQuery)
                    .withPageable(pageable)
                    .build();

            SearchHits<ChunkSearchDoc> searchHits = elasticsearchOperations.search(nativeQuery, ChunkSearchDoc.class);

            List<RankedChunk> hits = new ArrayList<>();
            int rank = 1;
            for (SearchHit<ChunkSearchDoc> hit : searchHits) {
                ChunkSearchDoc content = hit.getContent();
                Long chunkId = content.getChunkId();
                if (chunkId == null) {
                    continue;
                }
                hits.add(RankedChunk.bm25(chunkId, content.getText(), content.getChunkIndex(),
                        content.getPageNumber(), content.getSectionTitle(), content.getContentType(), content.getSourcePath(),
                        hit.getScore(), rank++));
            }
            return hits;
        } catch (Exception ex) {
            log.warn("BM25 recall fallback to vector-only. documentId={}, reason={}", documentId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RankedChunk> rrfFuse(List<RankedChunk> vectorHits, List<RankedChunk> bm25Hits, int k) {
        Map<Long, FusionAccumulator> acc = new LinkedHashMap<>();
        addRrf(acc, vectorHits, Math.max(1, k));
        addRrf(acc, bm25Hits, Math.max(1, k));
        return toRankedChunks(acc);
    }

    private void addRrf(Map<Long, FusionAccumulator> acc, List<RankedChunk> hits, int k) {
        for (RankedChunk hit : hits) {
            double addScore = 1.0 / (k + hit.rank());
            acc.compute(hit.chunkId(), (id, old) -> old == null
                    ? FusionAccumulator.from(hit, addScore)
                    : old.merge(addScore, hit));
        }
    }

    private List<RankedChunk> weightedFuse(List<RankedChunk> vectorHits,
                                           List<RankedChunk> bm25Hits,
                                           double vWeight,
                                           double bWeight) {
        Map<Long, FusionAccumulator> acc = new LinkedHashMap<>();
        for (RankedChunk hit : vectorHits) {
            double addScore = hit.score() * vWeight;
            acc.compute(hit.chunkId(), (id, old) -> old == null
                    ? FusionAccumulator.from(hit, addScore)
                    : old.merge(addScore, hit));
        }
        for (RankedChunk hit : bm25Hits) {
            double addScore = hit.score() * bWeight;
            acc.compute(hit.chunkId(), (id, old) -> old == null
                    ? FusionAccumulator.from(hit, addScore)
                    : old.merge(addScore, hit));
        }
        return toRankedChunks(acc);
    }

    private List<RankedChunk> toRankedChunks(Map<Long, FusionAccumulator> acc) {
        List<FusionAccumulator> sorted = acc.values().stream()
                .sorted(Comparator.comparingDouble(FusionAccumulator::score).reversed())
                .toList();
        List<RankedChunk> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            result.add(sorted.get(i).toRankedChunk(i + 1));
        }
        return result;
    }

    /**
     * Cross-encoder rerank of the fusion candidates through the rerank API.
     * Returns null when the API is disabled or fails, so the caller can fall
     * back to the fusion ordering. Candidates beyond the limit keep their
     * fusion order behind the reranked head.
     */
    private List<RankedChunk> apiRerank(String question, List<RankedChunk> fused) {
        if (!rerankService.isEnabled() || fused.isEmpty()) {
            return null;
        }
        List<RankedChunk> candidates = fused.stream()
                .limit(Math.max(1, rerankCandidateLimit))
                .toList();
        List<RerankService.RerankHit> hits = rerankService.rerank(
                question,
                candidates.stream().map(RankedChunk::text).toList());
        if (hits.isEmpty()) {
            return null;
        }
        List<RankedChunk> result = new ArrayList<>();
        for (RerankService.RerankHit hit : hits) {
            result.add(candidates.get(hit.index()).withScore(hit.relevanceScore()));
        }
        if (fused.size() > candidates.size()) {
            result.addAll(fused.subList(candidates.size(), fused.size()));
        }
        return result;
    }

    private List<RankedChunk> lightweightRerank(List<RankedChunk> fused) {
        if (fused.isEmpty()) {
            return fused;
        }
        double maxScore = fused.stream()
                .mapToDouble(RankedChunk::score)
                .max()
                .orElse(1.0);
        double safeMax = maxScore <= 0 ? 1.0 : maxScore;
        return fused.stream()
                .map(hit -> {
                    double normalizedRetrieval = hit.score() / safeMax;
                    return hit.withScore(normalizedRetrieval);
                })
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed())
                .toList();
    }

    private List<RagChunkEvidence> buildEvidence(List<RankedChunk> rankedChunks) {
        if (rankedChunks.isEmpty()) {
            return List.of();
        }
        double maxScore = rankedChunks.stream()
                .mapToDouble(RankedChunk::score)
                .max()
                .orElse(1.0);
        double safeMax = maxScore <= 0 ? 1.0 : maxScore;
        List<RagChunkEvidence> evidence = new ArrayList<>();
        for (int i = 0; i < rankedChunks.size(); i++) {
            RankedChunk hit = rankedChunks.get(i);
            double normalizedScore = hit.score() / safeMax;
            double absoluteSignal = Math.max(hit.vectorScore(), Math.min(hit.bm25Score() / 10.0, 1.0));
            double confidence = clamp(0.70 * normalizedScore + 0.30 * absoluteSignal);
            evidence.add(new RagChunkEvidence(
                    hit.chunkId(),
                    hit.text(),
                    hit.chunkIndex(),
                    hit.pageNumber(),
                    hit.sectionTitle(),
                    hit.contentType(),
                    hit.sourcePath(),
                    "S" + (i + 1),
                    hit.sourceType().name(),
                    hit.score(),
                    confidence,
                    hit.vectorScore(),
                    hit.bm25Score(),
                    hit.vectorRank(),
                    hit.bm25Rank()
            ));
        }
        return evidence;
    }

    private RagSearchResult buildSearchResult(String question, List<RagChunkEvidence> evidence) {
        if (evidence.isEmpty()) {
            return new RagSearchResult(question, evidence, 0.0, "低", "没有检索到可用证据。");
        }
        double top = evidence.get(0).getConfidence();
        double avgTop3 = evidence.stream()
                .limit(3)
                .mapToDouble(RagChunkEvidence::getConfidence)
                .average()
                .orElse(0.0);
        double enoughEvidence = Math.min(evidence.size() / 3.0, 1.0);
        double confidence = clamp(0.55 * top + 0.35 * avgTop3 + 0.10 * enoughEvidence);
        String level = confidence >= 0.72 ? "高" : confidence >= 0.45 ? "中" : "低";
        String reason = "基于融合检索分数、Top证据相关度和证据数量综合计算；该值用于提示检索可靠性，不等同于事实正确率。";
        return new RagSearchResult(question, evidence, confidence, level, reason);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private enum SourceType {
        VECTOR,
        BM25,
        HYBRID
    }

    private record RankedChunk(Long chunkId,
                               String text,
                               Integer chunkIndex,
                               Integer pageNumber,
                               String sectionTitle,
                               String contentType,
                               String sourcePath,
                               double score,
                               int rank,
                               SourceType sourceType,
                               double vectorScore,
                               double bm25Score,
                               Integer vectorRank,
                               Integer bm25Rank) {

        private static RankedChunk vector(Long chunkId, String text, Integer chunkIndex,
                                          Integer pageNumber, String sectionTitle, String contentType, String sourcePath,
                                          double score, int rank) {
            return new RankedChunk(chunkId, text, chunkIndex, pageNumber, sectionTitle, contentType, sourcePath,
                    score, rank, SourceType.VECTOR, score, 0.0, rank, null);
        }

        private static RankedChunk bm25(Long chunkId, String text, Integer chunkIndex,
                                        Integer pageNumber, String sectionTitle, String contentType, String sourcePath,
                                        double score, int rank) {
            return new RankedChunk(chunkId, text, chunkIndex, pageNumber, sectionTitle, contentType, sourcePath,
                    score, rank, SourceType.BM25, 0.0, score, null, rank);
        }

        private RankedChunk withScore(double newScore) {
            return new RankedChunk(chunkId, text, chunkIndex, pageNumber, sectionTitle, contentType, sourcePath,
                    newScore, rank, sourceType, vectorScore, bm25Score, vectorRank, bm25Rank);
        }
    }

    private record FusionAccumulator(Long chunkId,
                                     String text,
                                     Integer chunkIndex,
                                     Integer pageNumber,
                                     String sectionTitle,
                                     String contentType,
                                     String sourcePath,
                                     double score,
                                     double vectorScore,
                                     double bm25Score,
                                     Integer vectorRank,
                                     Integer bm25Rank) {

        private static FusionAccumulator from(RankedChunk hit, double initScore) {
            return new FusionAccumulator(
                    hit.chunkId(),
                    hit.text(),
                    hit.chunkIndex(),
                    hit.pageNumber(),
                    hit.sectionTitle(),
                    hit.contentType(),
                    hit.sourcePath(),
                    initScore,
                    hit.vectorScore(),
                    hit.bm25Score(),
                    hit.vectorRank(),
                    hit.bm25Rank()
            );
        }

        private FusionAccumulator merge(double addScore, RankedChunk fallback) {
            String candidateText = (this.text == null || this.text.isBlank()) ? fallback.text() : this.text;
            Integer candidateIndex = this.chunkIndex == null ? fallback.chunkIndex() : this.chunkIndex;
            Integer candidatePage = this.pageNumber == null ? fallback.pageNumber() : this.pageNumber;
            String candidateSection = (this.sectionTitle == null || this.sectionTitle.isBlank()) ? fallback.sectionTitle() : this.sectionTitle;
            String candidateContentType = (this.contentType == null || this.contentType.isBlank()) ? fallback.contentType() : this.contentType;
            String candidateSourcePath = (this.sourcePath == null || this.sourcePath.isBlank()) ? fallback.sourcePath() : this.sourcePath;
            double nextVectorScore = Math.max(this.vectorScore, fallback.vectorScore());
            double nextBm25Score = Math.max(this.bm25Score, fallback.bm25Score());
            Integer nextVectorRank = this.vectorRank == null ? fallback.vectorRank() : minRank(this.vectorRank, fallback.vectorRank());
            Integer nextBm25Rank = this.bm25Rank == null ? fallback.bm25Rank() : minRank(this.bm25Rank, fallback.bm25Rank());
            return new FusionAccumulator(
                    this.chunkId,
                    candidateText,
                    candidateIndex,
                    candidatePage,
                    candidateSection,
                    candidateContentType,
                    candidateSourcePath,
                    this.score + addScore,
                    nextVectorScore,
                    nextBm25Score,
                    nextVectorRank,
                    nextBm25Rank
            );
        }

        private RankedChunk toRankedChunk(int rank) {
            SourceType sourceType = vectorScore > 0 && bm25Score > 0
                    ? SourceType.HYBRID
                    : vectorScore > 0 ? SourceType.VECTOR : SourceType.BM25;
            return new RankedChunk(chunkId, text, chunkIndex, pageNumber, sectionTitle, contentType, sourcePath,
                    score, rank, sourceType,
                    vectorScore, bm25Score, vectorRank, bm25Rank);
        }

        private Integer minRank(Integer first, Integer second) {
            if (first == null) {
                return second;
            }
            if (second == null) {
                return first;
            }
            return Math.min(first, second);
        }
    }
}
