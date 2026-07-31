package io.github.ciurlaro.codexmobile.app.ui.chat

import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocation
import io.github.ciurlaro.codexmobile.app.presentation.invocation.PromptInvocationKind
import io.github.ciurlaro.codexmobile.app.ui.icons.IconGlyph
import io.github.ciurlaro.codexmobile.app.ui.theme.ChatColors

internal fun PromptInvocation.glyph(): IconGlyph =
    if (kind == PromptInvocationKind.SKILL) IconGlyph.SPARKLES else IconGlyph.PUZZLE

internal fun PromptInvocation.accent() =
    if (kind == PromptInvocationKind.SKILL) ChatColors.SkillAccent else ChatColors.PluginAccent
