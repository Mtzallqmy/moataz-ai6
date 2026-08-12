from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

path = Path("app/src/main/java/me/rerere/rikkahub/service/ChatService.kt")
text = path.read_text()

old_warning = '''            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

'''
text = replace_once(text, old_warning, "", "remove pre-runtime tool warning")

anchor = '''            } catch (error: InvalidMcpServerNameException) {
                throw IllegalStateException(
                    context.getString(
                        R.string.error_mcp_invalid_server_name,
                        error.invalidNames.joinToString(", "),
                    ),
                    error,
                )
            }

            generationHandler.generateText(
'''
replacement = '''            } catch (error: InvalidMcpServerNameException) {
                throw IllegalStateException(
                    context.getString(
                        R.string.error_mcp_invalid_server_name,
                        error.invalidNames.joinToString(", "),
                    ),
                    error,
                )
            }
            val toolsForGeneration = if (model.abilities.contains(ModelAbility.TOOL)) {
                runtimePlan.tools
            } else {
                if (runtimePlan.tools.isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable),
                    )
                }
                emptyList()
            }

            generationHandler.generateText(
'''
text = replace_once(text, anchor, replacement, "runtime capability guard")
text = replace_once(
    text,
    "                tools = runtimePlan.tools,\n",
    "                tools = toolsForGeneration,\n",
    "generation tools guard",
)
path.write_text(text)
