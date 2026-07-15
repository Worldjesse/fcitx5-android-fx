/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CalculateAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.VoiceInputHoldEnd
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.ExpressionEvaluator
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.fcitx.fcitx5.android.utils.toast
import org.fcitx.fcitx5.android.utils.withBatchEdit
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior
    private val preferredVoiceInput by kbdPrefs.preferredVoiceInput

    private var backspaceSwipeState = Stopped

    private var voiceHoldActive = false

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate if available
            // Check for candidates in prediction mode (preedit empty but candidates available)
            val hasCandidates = horizontalCandidate.adapter.total > 0
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty() || hasCandidates) {
                // preedit not empty or prediction candidates available, select the first candidate
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    private fun switchToVoiceInput(): Boolean {
        val isPasswordField = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
        } ?: false
        if (isPasswordField) return false
        if (VoiceInputProviderManager.isProviderId(preferredVoiceInput)) {
            return VoiceInputProviderManager.toggle(
                service = service,
                id = preferredVoiceInput,
                onReady = {
                    VoiceInputProviderManager.voiceReadyCallback?.invoke()
                },
                onPartialResult = {},
                onError = { msg ->
                    VoiceInputProviderManager.voiceErrorCallback?.invoke(msg)
                },
                onLevel = { rms ->
                    VoiceInputProviderManager.voiceLevelCallback?.invoke(rms)
                },
                onFinished = {
                    VoiceInputProviderManager.voiceFinishedCallback?.invoke()
                },
                onStatus = { status ->
                    VoiceInputProviderManager.voiceStatusCallback?.invoke(status)
                },
            )
        }
        val (id, subtype) = InputMethodUtil.findVoiceSubtype(preferredVoiceInput) ?: return false
        InputMethodUtil.switchInputMethod(service, id, subtype)
        return true
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendKey(action.act, action.states.states, action.code, action.up)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)
                            ?.prepareCompanionKeyboardHeightPercentOverride()
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        SpaceLongPressBehavior.VoiceInput -> { switchToVoiceInput() }
                        SpaceLongPressBehavior.VoiceInputHold -> {
                            val started = switchToVoiceInput()
                            if (VoiceInputProviderManager.isProviderId(preferredVoiceInput)) {
                                voiceHoldActive = started
                            }
                        }
                    }
                }
                is VoiceInputHoldEnd -> {
                    if (voiceHoldActive) {
                        switchToVoiceInput()
                        voiceHoldActive = false
                    }
                }
                is CalculateAction -> {
                    val ic = service.currentInputConnection ?: return@KeyActionListener
                    // 已提交的文本
                    val before = ic.getTextBeforeCursor(1024, 0)?.toString().orEmpty()
                    // 输入法当前持有的合成文本。数字键盘输入常被 fcitx 暂存在 preedit 而非立即
                    // 提交，getTextBeforeCursor 取不到这段内容（部分编辑器甚至返回 null），
                    // 这正是原逻辑总是取到空串、提示"未找到可计算的表达式"的根因。
                    val preedit = fcitx.runImmediately { clientPreeditCached.toString() }
                        .ifBlank { fcitx.runImmediately { inputPanelCached.preedit.toString() } }
                    // 编辑器可能已把合成文本计入 before，避免重复拼接
                    val overlapped = preedit.isNotBlank() && before.endsWith(preedit)
                    val full = if (overlapped) before else before + preedit
                    val expr = ExpressionEvaluator.extractExpression(full)
                    if (expr == null) {
                        context.toast("未找到可计算的表达式")
                    } else {
                        ExpressionEvaluator.evaluate(expr).fold(
                            onSuccess = { value ->
                                val formatted = ExpressionEvaluator.formatResult(value)
                                // 表达式落在 preedit 中的长度，决定是否需要先清合成区、以及要从
                                // 已提交文本中删掉多少
                                val committedExprLen = if (overlapped) {
                                    expr.length
                                } else {
                                    maxOf(0, expr.length - preedit.length)
                                }
                                service.postFcitxJob {
                                    // 丢弃合成区（不提交），避免与结果重复
                                    if (preedit.isNotBlank()) reset()
                                    service.lifecycleScope.launch {
                                        ic.withBatchEdit {
                                            if (committedExprLen > 0) {
                                                ic.deleteSurroundingText(committedExprLen, 0)
                                            }
                                            ic.finishComposingText()
                                            ic.commitText(formatted, 1)
                                        }
                                    }
                                }
                            },
                            onFailure = { e -> context.toast(e.message ?: "计算失败") }
                        )
                    }
                }
                else -> {}
            }
        }
    }
}
