package com.example.agent.tool.react;

import com.example.agent.entity.AgentSkillDoc;
import com.example.agent.service.SkillLibraryService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Progressive disclosure entry point of the skill library: the planner only
 * sees skill names and descriptions in its system prompt, and calls this tool
 * to pull a skill's full instruction body into the observation context.
 */
@Component
public class UseSkillTool implements ReactTool {

    private final SkillLibraryService skillLibraryService;

    public UseSkillTool(SkillLibraryService skillLibraryService) {
        this.skillLibraryService = skillLibraryService;
    }

    @Override
    public String name() {
        return "use_skill";
    }

    @Override
    public String description() {
        return "Load the full instructions of a skill from the skill library. "
                + "Call this first when the current task matches a skill description, "
                + "then follow the loaded instructions for the rest of the task. "
                + "Multiple skills can be loaded in one task.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "skillName": "string, required, the exact skill name from the available skills list"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String skillName = ToolArguments.string(arguments, "skillName");
        if (skillName == null || skillName.isBlank()) {
            return ToolExecutionResult.failure("skillName is required.");
        }
        Optional<AgentSkillDoc> skill = skillLibraryService.load(context.userId(), skillName);
        if (skill.isEmpty()) {
            return ToolExecutionResult.failure(
                    "Skill '" + skillName + "' is not available. Available skills:\n"
                            + skillLibraryService.catalog(context.userId()));
        }
        AgentSkillDoc doc = skill.get();
        return ToolExecutionResult.success("""
                [SKILL LOADED] %s
                %s

                Follow these skill instructions for the remaining steps of this task:

                %s
                """.formatted(doc.getName(), doc.getDescription(), doc.getContent()).strip());
    }
}
