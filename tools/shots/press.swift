// Presses keys and moves the mouse in the game the way a player does, for shoot.py's scenes:
//   press escape            closes the window on screen (a dialog, the chat)
//   press chat              opens the chat with T, its history and its buttons clickable
//   press move <fx> <fy>    puts the pointer at fractions of the game's window, title bar included
//   press click <fx> <fy>   the same, then a left click there
//   press hold <key> <s>     holds a letter key (w) for <s> seconds: the player walks
//   press use               a right click where the player looks: opens the chest under the crosshair
// macOS asks for Accessibility for the terminal before it passes the keys on.
import AppKit
import ApplicationServices

let args = CommandLine.arguments
let windows = CGWindowListCopyWindowInfo([.optionAll], kCGNullWindowID) as! [[String: Any]]
guard let game = windows.first(where: { (($0[kCGWindowName as String] as? String) ?? "").hasPrefix("Minecraft ") }),
      let pid = game[kCGWindowOwnerPID as String] as? pid_t,
      let app = NSRunningApplication(processIdentifier: pid),
      let bounds = game[kCGWindowBounds as String] as? [String: CGFloat] else {
  print("no game window"); exit(1)
}
app.activate()
AXUIElementSetAttributeValue(AXUIElementCreateApplication(pid), kAXFrontmostAttribute as CFString, kCFBooleanTrue)
usleep(400_000)

func key(_ code: CGKeyCode) {
  for down in [true, false] {
    CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)?.postToPid(pid)
    usleep(30_000)
  }
}

// The game reads the pointer from the system, so these go through the HID tap, not to the game's process alone.
func point(_ fx: Double, _ fy: Double) -> CGPoint {
  CGPoint(x: bounds["X"]! + bounds["Width"]! * fx, y: bounds["Y"]! + bounds["Height"]! * fy)
}

func move(to target: CGPoint) {
  // A few steps, so the game sees the pointer travel and lights the button under it.
  let start = CGEvent(source: nil)?.location ?? target
  for step in 1...12 {
    let t = Double(step) / 12
    let at = CGPoint(x: start.x + (target.x - start.x) * t, y: start.y + (target.y - start.y) * t)
    CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: at, mouseButton: .left)?.post(tap: .cghidEventTap)
    usleep(25_000)
  }
}

switch args[1] {
case "escape":
  key(53)
case "chat":
  key(17)  // T
case "move":
  move(to: point(Double(args[2])!, Double(args[3])!))
case "click":
  let at = point(Double(args[2])!, Double(args[3])!)
  move(to: at)
  usleep(500_000)
  for type in [CGEventType.leftMouseDown, .leftMouseUp] {
    CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: at, mouseButton: .left)?.post(tap: .cghidEventTap)
    usleep(60_000)
  }
case "use":
  // The game holds the pointer while it plays, so the click goes where the crosshair is.
  let at = CGEvent(source: nil)?.location ?? point(0.5, 0.5)
  for type in [CGEventType.rightMouseDown, .rightMouseUp] {
    CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: at, mouseButton: .right)?.post(tap: .cghidEventTap)
    usleep(60_000)
  }
case "hold":
  let codes: [String: CGKeyCode] = ["w": 13, "a": 0, "s": 1, "d": 2]
  guard let code = codes[args[2]] else { print("hold takes w, a, s, or d"); exit(1) }
  CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true)?.postToPid(pid)
  usleep(useconds_t(Double(args[3])! * 1_000_000))
  CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false)?.postToPid(pid)
default:
  print("escape, chat, move <fx> <fy>, click <fx> <fy>, use, or hold <key> <seconds>"); exit(1)
}
