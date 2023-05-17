package org.liuyi.mifreeformx.xposed.hooker

import android.graphics.Rect
import android.view.MotionEvent
import com.highcapable.yukihookapi.hook.factory.constructor
import com.highcapable.yukihookapi.hook.xposed.prefs.data.PrefsData
import org.liuyi.mifreeformx.proxy.framework.LocalServices
import org.liuyi.mifreeformx.proxy.framework.MiuiFreeFormActivityStack
import org.liuyi.mifreeformx.proxy.framework.MiuiFreeFormGestureController
import org.liuyi.mifreeformx.proxy.framework.MiuiFreeFormGesturePointerEventListener
import org.liuyi.mifreeformx.proxy.framework.MiuiFreeFormManagerService
import org.liuyi.mifreeformx.proxy.framework.WindowManagerService
import org.liuyi.mifreeformx.utils.RectUtils
import org.liuyi.mifreeformx.utils.callMethodByName
import org.liuyi.mifreeformx.utils.getFieldValueOrNull
import org.liuyi.mifreeformx.utils.setFieldValue
import org.liuyi.mifreeformx.xposed.base.LyBaseHooker
import org.liuyi.mifreeformx.xposed.operation.FreeFormOutsideActionOpt
import kotlin.math.roundToInt

/**
 * @Author: Liuyi
 * @Date: 2023/05/16/13:43:06
 * @Description:
 */
object FreeformOutsideMotionHooker : LyBaseHooker() {

    val CLICK_FREEFORM_OUTSIDE_ACTION_TYPE = PrefsData("click_freeform_outside_action_type", 0)
    val CLICK_FREEFORM_OUTSIDE_ACTION_TYPE_STRING = listOf("默认", "迷你小窗", "贴边小窗", "关闭小窗")

    val FREEFORM_OUTSIDE_MOTION_MODE = PrefsData("freeform_outside_motion_mode", 0)
    val FREEFORM_OUTSIDE_MOTION_MODE_STRING = listOf("无", "点击", "触摸")

    override fun onHook() {
        "com.android.server.wm.MiuiFreeFormGesturePointerEventListener".hook {
            injectMember {
                method { name = "onActionUp" }
                afterHook {
                    if (prefs.get(CLICK_FREEFORM_OUTSIDE_ACTION_TYPE) == 0
                        || prefs.get(FREEFORM_OUTSIDE_MOTION_MODE) != 1
                    ) return@afterHook
                    logD(msg = "onActionUp: 参数：${args.toList()}")
                    val stack = args[0]?.getFieldValueOrNull("mffas")
                    if (stack != null && stack.getFieldValueOrNull("mStackID") == -1) {
                        // 点击小窗外事件
                        val listener = instance.getProxyAs<MiuiFreeFormGesturePointerEventListener>()
                        val mGestureController = listener.mGestureController!!
                        val mfmService = mGestureController.mMiuiFreeFormManagerService!!
                        val wmService = mGestureController.mService!!
                        val stackList = mfmService.getAllMiuiFreeFormActivityStack().filterNotNull()
                        val event = args[0]?.getFieldValueOrNull("motionEvent") as MotionEvent?
                        if (event != null) {
                            val matchStack = findFreeFormActivityStackByPosition(stackList, event.x, event.y)
                            logD("在位置[${event.x}, ${event.y}] 抬起，此处：$matchStack")
                            if (matchStack != null) {
                                return@afterHook
                            }
                        }

                        logD("判断为点击外面事件，处理其他小窗")
                        when (prefs.get(CLICK_FREEFORM_OUTSIDE_ACTION_TYPE)) {
                            1 -> handleToMiniWindow(stackList, wmService)

                            2 -> FreeFormOutsideActionOpt.allFreeFromToPin(wmService)
                            3 -> handleExitApplication(stackList, listener)
                        }

                    }
                }
            }
        }

        /**
         * 当该函数返回为null时构造一个假的MiuiFreeFormActivityStack对象返回，
         * 要求：
         * 1. mMiuiFreeFromWindowMode 不等于 1
         * 2. 存在 mTask ×
         *
         * 判断 mInMultiTouch 为 false
         * 处理：修改MiuiMultiWindowUtils.MULTI_WINDOW_SWITCH_ENABLED 为 false
         */
        "com.android.server.wm.MiuiFreeFormGesturePointerEventListener".hook {
            injectMember {
                method { name = "synchronizeControlInfoForeMoveEvent" }
                afterHook {
                    if (prefs.get(CLICK_FREEFORM_OUTSIDE_ACTION_TYPE) == 0
                        && prefs.get(FREEFORM_OUTSIDE_MOTION_MODE) == 0
                    ) return@afterHook
                    logD(msg = "synchronizeControlInfoForeMoveEvent: 参数：${args.toList()}, 返回值：$result")
                    val listener = instance.getProxyAs<MiuiFreeFormGesturePointerEventListener>()
                    val event = args[0] as MotionEvent?

                    if (result == null && !listener.mInMultiTouch && event != null) {
                        val mGestureController = listener.mGestureController!!
                        val imwVisibleHeight = getInputMethodVisibleHeight(mGestureController)
                        logD("当前软键盘高度：$imwVisibleHeight")
                        if (imwVisibleHeight != 0) return@afterHook

                        val mfmService = mGestureController.mMiuiFreeFormManagerService!!
                        val stackList = mfmService.getAllMiuiFreeFormActivityStack().filterNotNull()
                        val stack = findFreeFormActivityStackByPosition(stackList, event.x, event.y)
                        if (stack != null) {
                            logD("点击在小窗上，取消触发：$stack")
                            return@afterHook
                        }

                        when (prefs.get(FREEFORM_OUTSIDE_MOTION_MODE)) {
                            1 -> {
                                // 点击动作
                                val fakeStack = stackList[0]::class.java.constructor {
                                    param("com.android.server.wm.MiuiFreeFormActivityStack")
                                }.get().newInstance<Any>(stackList[0])!!
                                fakeStack.setFieldValue("mMiuiFreeFromWindowMode", -1)
                                // 制作标记
                                fakeStack.setFieldValue("mStackID", -1)
                                logD("放置假Stack：$fakeStack")
                                result = fakeStack
                            }

                            2 -> {
                                // 触摸动作
                                logD("执行触摸动作")
                                val wmService = mGestureController.mService!!
                                when (prefs.get(CLICK_FREEFORM_OUTSIDE_ACTION_TYPE)) {
                                    1 -> {
                                        handleToMiniWindow(stackList, wmService)
                                    }

                                    2 -> FreeFormOutsideActionOpt.allFreeFromToPin(wmService)
                                    3 -> handleExitApplication(stackList, listener)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findFreeFormActivityStackByPosition(mfmService: MiuiFreeFormManagerService, x: Float, y: Float) =
        findFreeFormActivityStackByPosition(mfmService.getAllMiuiFreeFormActivityStack().filterNotNull(), x, y)


    private fun findFreeFormActivityStackByPosition(stackList: List<Any>, x: Float, y: Float) =
        stackList.map {
            it.getProxyAs<MiuiFreeFormActivityStack>()
        }.firstOrNull {
            val rect = it.mTask?.getConfiguration()
                ?.getFieldValueOrNull("windowConfiguration")
                ?.getFieldValueOrNull("mBounds") as? Rect?
            if (rect == null) false
            else {
                val scale = it.mFreeFormScale
                RectUtils.getScaledRect(rect, scale).contains(x.roundToInt(), y.roundToInt())
            }
        }

    private fun getInputMethodVisibleHeight(listener: MiuiFreeFormGestureController): Int {
        val displayId = listener.mDisplayContent?.callMethodByName("getDisplayId") as Int
        val wmInternal = LocalServices.StaticProxy.getService("com.android.server.wm.WindowManagerInternal".toClass())!!
        return wmInternal.callMethodByName("getInputMethodWindowVisibleHeight", displayId) as Int
    }

    private fun handleToMiniWindow(stackList: List<Any>, wmService: WindowManagerService) {
        stackList.forEach {
            kotlin.runCatching {
                FreeFormOutsideActionOpt.turnFreeFormToSmallWindow(
                    it.getFieldValueOrNull("mStackID") as Int,
                    wmService
                )
            }.exceptionOrNull()?.let { logE(e = it) }
        }
    }

    private fun handleExitApplication(stackList: List<Any>, listener: MiuiFreeFormGesturePointerEventListener) {
        stackList.forEach {
            kotlin.runCatching {
                val activityStack = it.getProxyAs<MiuiFreeFormActivityStack>()
                if (activityStack.isInFreeFormMode() && !activityStack.inPinMode()) {
                    listener.startExitApplication(activityStack)
                }
            }.exceptionOrNull()?.let { logE(e = it) }
        }
    }
}