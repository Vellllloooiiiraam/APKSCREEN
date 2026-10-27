package com.example.screentranslate.a11y

import android.view.WindowManager

/**
 * Ponte para o `WindowManager` do servico de acessibilidade.
 *
 * Adicionar uma janela `TYPE_ACCESSIBILITY_OVERLAY` exige um token que so existe no
 * `WindowManager` devolvido por `AccessibilityService.getSystemService(WINDOW_SERVICE)`:
 * o AOSP chama `WindowManagerImpl.setDefaultToken(mWindowToken)` nesse objeto, e e esse
 * token que o sistema valida. Sem ele, `addView` falha com
 * `BadTokenException: token null is not valid`.
 *
 * A troca vale a pena: uma janela de acessibilidade e um overlay **confiavel**, entao o
 * sistema NAO limita a opacidade a `LayoutParams.MAX_OBSCURING_OPACITY` (0.8) como faz
 * com janelas `TYPE_APPLICATION_OVERLAY` que nao recebem toque. Sem esse limite, a caixa
 * da traducao fica realmente opaca e esconde o texto original.
 */
object A11yWindowHost {

    @Volatile
    var windowManager: WindowManager? = null
        private set

    /**
     * Disparado quando o servico (re)conecta e passa a existir um `WindowManager`
     * novo. E necessario porque o sistema REMOVE as janelas de acessibilidade quando
     * o token do servico cai: sem recriar, a traducao some da tela e nunca volta.
     */
    @Volatile
    var onAttached: (() -> Unit)? = null

    fun attach(windowManager: WindowManager?) {
        this.windowManager = windowManager
        if (windowManager != null) onAttached?.invoke()
    }
}