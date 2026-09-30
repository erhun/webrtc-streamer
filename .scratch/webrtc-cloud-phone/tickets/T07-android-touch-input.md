# Android 客户端：触控 → 控制消息映射

**Blocked by**: T06

## 目标
将 Android 触屏事件（`onTouchEvent`）映射为 scrcpy 控制消息（`injectTouch`/`injectScroll`），通过 DataChannel 发送到服务端，实现触控操作云机。

## 涉及层
- [ ] UI 层：`TouchInputHandler.java` — `View.OnTouchListener` 实现
- [ ] UI 层：`MotionEvent` → 屏幕坐标归一化（0~1 浮点 → 绝对像素坐标）
- [ ] 逻辑层：`ACTION_DOWN` → `injectTouch(press, ...)`, `ACTION_MOVE` → `injectTouch(move, ...)`, `ACTION_UP` → `injectTouch(release, ...)`
- [ ] 逻辑层：双指缩放 → `injectScroll(...)`（可选，P2）
- [ ] 逻辑层：物理按键映射 `onKeyDown/onKeyUp` → `injectKeyCode(...)`（可选，P2）
- [ ] 测试：触屏操作云机，画面有响应

## 验收标准
- 单指点击云机屏幕，云机有响应（如点击图标）
- 单指滑动，云机有滚动/拖拽响应
- 多点触控至少支持 2 指（如游戏摇杆 + 技能键）
- 坐标映射准确，点击位置与云机响应位置一致
