/* QNotified - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2021 xenonhydride@gmail.com
 * https://github.com/ferredoxin/QNotified
 *
 * This software is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package nil.nadph.qnotified.hook;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.Parcelable;
import android.widget.Toast;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import me.kyuubiran.util.UtilsKt;
import me.singleneuron.qn_kernel.data.HostInformationProviderKt;
import nil.nadph.qnotified.ExfriendManager;
import nil.nadph.qnotified.SyncUtils;
import nil.nadph.qnotified.activity.ChatTailActivity;
import nil.nadph.qnotified.config.ConfigItems;
import nil.nadph.qnotified.config.ConfigManager;
import nil.nadph.qnotified.dialog.RikkaCustomMsgTimeFormatDialog;
import nil.nadph.qnotified.util.DexKit;
import nil.nadph.qnotified.util.LicenseStatus;
import nil.nadph.qnotified.util.Utils;

import static nil.nadph.qnotified.util.Initiator._SessionInfo;
import static nil.nadph.qnotified.util.Utils.*;

public class ChatTailHook extends CommonDelayableHook {
    public static final String qn_chat_tail_enable = "qn_chat_tail_enable";
    private static final String ACTION_UPDATE_CHAT_TAIL = "nil.nadph.qnotified.ACTION_UPDATE_CHAT_TAIL";
    private static final ChatTailHook self = new ChatTailHook();


    ChatTailHook() {
        super(qn_chat_tail_enable);
    }

    public static ChatTailHook get() {
        return self;
    }

    @Override
    public boolean initOnce() {
        try {
            Class facade = DexKit.doFindClass(DexKit.C_FACADE);
            Method m = null;
            for (Method mi : facade.getDeclaredMethods()) {
                if (!mi.getReturnType().equals(long[].class)) continue;
                Class[] argt = mi.getParameterTypes();
                if (argt.length != 6) continue;
                if (argt[1].equals(Context.class) && argt[2].equals(_SessionInfo())
                        && argt[3].equals(String.class) && argt[4].equals(ArrayList.class)) {
                    m = mi;
                    m.setAccessible(true);
                    break;
                }
            }

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isEnabled()) {
                        if (LicenseStatus.sDisableCommonHooks) return;
                        if (LicenseStatus.hasBlackFlags()) return;
                        String msg = (String) param.args[3];
                        String text = msg;
                        final Parcelable session = (Parcelable) param.args[2];
                        try {
                            String uin = "10000";
                            if (!isGlobal()) {
                                Field field = null;
                                for (Field f : session.getClass().getDeclaredFields()) {
                                    // 因为有多个同名变量，所以要判断返回类型
                                    if (f.getName().equalsIgnoreCase("a") && f.getType() == String.class) {
                                        field = f;
                                    }
                                }
                                if (null == field) field = session.getClass().getDeclaredField("curFriendUin");
                                uin = (String) field.get(session);
                            }
                            ChatTailHook ct = ChatTailHook.get();
                            logi("isRegex:" + String.valueOf(ChatTailHook.isRegex()));
                            logi("isPassRegex:" + String.valueOf(ChatTailHook.isPassRegex(msg)));
                            logi("getTailRegex:" + ChatTailHook.getTailRegex());
                            if ((ct.isGlobal() || ct.containsTroop(uin) || ct.containsFriend(uin))
                                    && (!isRegex() || !isPassRegex(msg))) {
                                int battery = FakeBatteryHook.get().isEnabled() ? FakeBatteryHook.get().getFakeBatteryStatus() < 1 ? ChatTailActivity.getBattery() : FakeBatteryHook.get().getFakeBatteryCapacity() : ChatTailActivity.getBattery();
                                text = ct.getTailCapacity()
                                        .replace(ChatTailActivity.delimiter, msg)
                                        .replace("#model#", Build.MODEL)
                                        .replace("#brand#", Build.BRAND)
                                        .replace("#battery#", battery + "")
                                        .replace("#power#", ChatTailActivity.getPower())
                                        .replace("#time#", new SimpleDateFormat(RikkaCustomMsgTimeFormatDialog.getTimeFormat()).format(new Date()));
                                if (ct.getTailCapacity().contains("#Spacemsg#")) {
                                    text = text.replace("#Spacemsg#", "");
                                    text = UtilsKt.makeSpaceMsg(text);
                                }
                            }
                        } catch (Throwable e) {
                            log(e);
                        } finally {
                            param.args[3] = text;
                        }
                    }
                }
            });
            return true;
        } catch (Throwable throwable) {
            log(throwable);
            return false;
        }
    }

    private boolean containsFriend(String uin) {
        String muted = "," + ExfriendManager.getCurrent().getConfig().getString(ConfigItems.qn_chat_tail_friends) + ",";
        return muted.contains("," + uin + ",");
    }

    private boolean isGlobal() {
        return ExfriendManager.getCurrent().getConfig().getBooleanOrFalse(ConfigItems.qn_chat_tail_global);
    }

    public static boolean isRegex() {
        return ExfriendManager.getCurrent().getConfig()
                .getBooleanOrFalse(ConfigItems.qn_chat_tail_regex);
    }

    /**
     * 通过正则表达式的消息不会携带小尾巴（主要是考虑到用户可能写错表达式）
     *
     * @param msg 原始聊天消息文本
     * @return 该消息是否通过指定的正则表达式
     */
    public static boolean isPassRegex(String msg) {
        try {
            return Pattern.compile(getTailRegex()).matcher(msg).find();
        } catch (PatternSyntaxException e) {
            XposedBridge.log(e);
            return false;
        }
    }

    private boolean containsTroop(String uin) {
        String muted = "," + ExfriendManager.getCurrent().getConfig().getString(ConfigItems.qn_chat_tail_troops) + ",";
        return muted.contains("," + uin + ",");
    }


    public void setTail(String tail) {
        try {
            ConfigManager cfg = ExfriendManager.getCurrent().getConfig();
            cfg.putString(ConfigItems.qn_chat_tail, tail);
            cfg.save();
        } catch (IOException e) {
            log(e);
        }
    }

    public String getTailStatus() {
        return ExfriendManager.getCurrent().getConfig().getStringOrDefault(ConfigItems.qn_chat_tail, "");
    }

    public String getTailCapacity() {
        return getTailStatus().replace("\\n", "\n");
    }

    public static void setTailRegex(String regex) {
        try {
            ConfigManager cfg = ExfriendManager.getCurrent().getConfig();
            cfg.putString(ConfigItems.qn_chat_tail_regex_text, regex);
            cfg.save();
        } catch (IOException e) {
            log(e);
        }
    }

    public static String getTailRegex() {
        // (?:(?![A-Za-z0-9])(?:[\x21-\x7e？！]))$
        return ExfriendManager.getCurrent().getConfig()
                .getStringOrDefault(ConfigItems.qn_chat_tail_regex_text, "");
    }

    @Override
    public void setEnabled(boolean enabled) {
        try {
            ConfigManager cfg = ExfriendManager.getCurrent().getConfig();
            cfg.putBoolean(qn_chat_tail_enable, enabled);
            cfg.save();
        } catch (final Exception e) {
            Utils.log(e);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Utils.showToast(HostInformationProviderKt.getHostInformationProvider().getApplicationContext(), TOAST_TYPE_ERROR, e + "", Toast.LENGTH_SHORT);
            } else {
                SyncUtils.post(() -> Utils.showToast(HostInformationProviderKt.getHostInformationProvider().getApplicationContext(), TOAST_TYPE_ERROR, e + "", Toast.LENGTH_SHORT));
            }
        }
    }

    @Override
    public boolean isEnabled() {
        try {
            return ExfriendManager.getCurrent().getBooleanOrDefault(qn_chat_tail_enable, false);
        } catch (IllegalArgumentException e) {
            return false;
        } catch (Exception e) {
            log(e);
            return false;
        }
    }
}
