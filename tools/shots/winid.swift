// Prints the window number of the game window shoot.py launched, so screencapture takes that window only.
import CoreGraphics
let list = CGWindowListCopyWindowInfo([.optionAll], kCGNullWindowID) as! [[String: Any]]
for w in list {
  let name = w[kCGWindowName as String] as? String ?? ""
  if name.hasPrefix("Minecraft ") && name.contains("26.") { print(w[kCGWindowNumber as String]!); break }
}
