// Types into the game the way a player does, for shoot.py's animation: brings the game's window to the front,
// presses / (the game opens the chat with it), types the text a letter at a time, and presses Enter.
//   swift type.swift "<text>"
// macOS asks for Accessibility for the terminal before it passes the keys on.
import AppKit
import ApplicationServices

let text = CommandLine.arguments[1]
let windows = CGWindowListCopyWindowInfo([.optionAll], kCGNullWindowID) as! [[String: Any]]
guard let game = windows.first(where: { (($0[kCGWindowName as String] as? String) ?? "").hasPrefix("Minecraft ") }),
      let pid = game[kCGWindowOwnerPID as String] as? pid_t,
      let app = NSRunningApplication(processIdentifier: pid) else {
  print("no game window"); exit(1)
}
// A background process may not bring another app forward on its own; through Accessibility it may.
app.activate()
AXUIElementSetAttributeValue(AXUIElementCreateApplication(pid), kAXFrontmostAttribute as CFString, kCFBooleanTrue)
usleep(600_000)

func key(_ code: CGKeyCode) {
  for down in [true, false] {
    CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)?.postToPid(pid)
    usleep(20_000)
  }
}

// Any letter, Cyrillic or Spanish too, goes as itself, whatever the keyboard layout.
func letter(_ character: Character) {
  let units = Array(String(character).utf16)
  for down in [true, false] {
    let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
    event?.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
    event?.postToPid(pid)
    usleep(8_000)
  }
}

key(44)  // the slash key: the chat opens with / in it
usleep(350_000)
for character in text {
  letter(character)
  usleep(useconds_t(character == " " ? 90_000 : Int.random(in: 35_000...75_000)))
}
usleep(400_000)
key(36)  // Enter
