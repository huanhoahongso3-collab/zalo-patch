package com.ez.zalopatch.xposed.features;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

/**
 * Sends arbitrary emoji or short text as a message reaction, the way the Zalo Web
 * "custom reaction" userscripts do.
 *
 * <p>The host builds a reaction from a type id and an icon string and sends it through one
 * static helper; the type id is only a number on the wire ({@code rType}/{@code rIcon}) and
 * unknown icons are rendered by the recipient from {@code rIcon}. This feature adds a small
 * button to the long-press reaction bar, asks for the icon, and calls that same helper.
 * It does not alter entitlement checks or any other host behavior.
 */
public final class CustomReactionFeature extends Feature {
    private static final String FEATURE = Tweaks.KEY_CUSTOM_REACTION;
    private static final String PICKER_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerInContextMenuView";
    private static final String BUTTON_TAG = "zalopatch.custom_reaction";
    private static final int MAX_ICON_CODE_POINTS = 24;

    private final String sendClass;
    private final String sendMethod;
    private final String factoryClass;
    private final String factoryMethod;
    private final String messageField;
    private final String dismissMethod;

    public CustomReactionFeature(ClassLoader classLoader) {
        super(classLoader);
        SymbolSchema.Active schema =
                SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
        sendClass = schema.string("symbols.chat.custom_reaction_send_class", "");
        sendMethod = schema.string("symbols.chat.custom_reaction_send_method", "");
        factoryClass = schema.string("symbols.chat.custom_reaction_factory_class", "");
        factoryMethod = schema.string("symbols.chat.custom_reaction_factory_method", "");
        messageField = schema.string("symbols.chat.custom_reaction_message_field", "");
        dismissMethod = schema.string("symbols.chat.custom_reaction_dismiss_method", "");
    }

    @Override
    public String getFeatureName() {
        return "CustomReaction";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_CUSTOM_REACTION)) {
            SelfCheckRegistry.markDisabled(FEATURE, "custom reaction button");
            return;
        }
        String target = sendClass + "#" + sendMethod + "() via " + PICKER_CLASS;
        if (sendClass.isEmpty() || sendMethod.isEmpty() || factoryClass.isEmpty()
                || factoryMethod.isEmpty() || messageField.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE, target,
                    "reaction send symbols are not mapped for this Zalo build");
            return;
        }
        runGuarded("custom reaction button", FEATURE, target, () -> {
            Class<?> picker = XpReflect.findClass(PICKER_CLASS, classLoader);
            // Fail early, not on first tap, when the mapped symbols have drifted.
            XpReflect.findClass(sendClass, classLoader);
            XpReflect.findClass(factoryClass, classLoader);
            XpHooks.hookAllConstructors(FEATURE, picker, null, new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        attachButton((ViewGroup) param.thisObject);
                    }
                }
            });
        });
    }

    private void attachButton(ViewGroup picker) {
        try {
            for (int i = 0; i < picker.getChildCount(); i++) {
                if (BUTTON_TAG.equals(picker.getChildAt(i).getTag())) {
                    return;
                }
            }
            Context context = picker.getContext();
            TextView button = new TextView(context);
            button.setTag(BUTTON_TAG);
            button.setText("+");
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(0xFF808080);
            int size = dp(context, 28);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size,
                    Gravity.END | Gravity.TOP);
            params.setMargins(0, dp(context, 2), dp(context, 2), 0);
            button.setOnClickListener(v -> promptAndSend(picker));
            picker.addView(button, params);
            SelfCheckRegistry.incrementHit(FEATURE, PICKER_CLASS, "custom reaction button added");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, PICKER_CLASS, throwable);
        }
    }

    private void promptAndSend(ViewGroup picker) {
        Context context = picker.getContext();
        try {
            // Host process: module resource ids are not reliable here, so strings are inline.
            boolean vi = "vi".equals(java.util.Locale.getDefault().getLanguage());
            EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setSingleLine(true);
            input.setHint(vi ? "Emoji hoặc chữ ngắn" : "Emoji or short text");
            new AlertDialog.Builder(context)
                    .setTitle(vi ? "Cảm xúc tùy chỉnh" : "Custom reaction")
                    .setView(input)
                    .setPositiveButton(vi ? "Gửi" : "Send",
                            (dialog, which) -> send(picker, input.getText().toString().trim()))
                    .setNegativeButton(vi ? "Hủy" : "Cancel", null)
                    .show();
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, "prompt", throwable);
        }
    }

    private void send(ViewGroup picker, String icon) {
        if (icon.isEmpty()) {
            return;
        }
        if (icon.codePointCount(0, icon.length()) > MAX_ICON_CODE_POINTS) {
            icon = icon.substring(0, icon.offsetByCodePoints(0, MAX_ICON_CODE_POINTS));
        }
        try {
            Object listener = XpReflect.callMethod(picker, "getListener");
            Object message = XpReflect.getObjectField(listener, messageField);
            if (message == null) {
                throw new IllegalStateException("no message bound to the reaction bar");
            }
            Class<?> factory = XpReflect.findClass(factoryClass, classLoader);
            Class<?> sender = XpReflect.findClass(sendClass, classLoader);
            Object reaction = XpReflect.callStaticMethod(factory, factoryMethod,
                    typeFor(icon), icon);
            XpReflect.callStaticMethod(sender, sendMethod, reaction, message, 0, true);
            SelfCheckRegistry.incrementHit(FEATURE, sendClass + "#" + sendMethod,
                    "custom reaction sent");
            if (!dismissMethod.isEmpty()) {
                try {
                    XpReflect.callMethod(listener, dismissMethod);
                } catch (Throwable ignored) {
                    // The reaction is already sent; leaving the menu open is harmless.
                }
            }
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, sendClass + "#" + sendMethod, throwable);
            Toast.makeText(picker.getContext(), "Custom reaction failed", Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /** Same djb2-style hash the Zalo Web userscript uses for {@code rType}, kept non-negative. */
    static int typeFor(String icon) {
        int hash = 0;
        for (int i = 0; i < icon.length(); i++) {
            hash = (hash << 5) - hash + icon.charAt(i);
        }
        return hash & 0x7fffffff;
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

}
