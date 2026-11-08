// Records the game's window alone, at 30 frames a second in sRGB, for shoot.py's animations:
//   swift record.swift <seconds> <out.mov> [cursor]
// ScreenCaptureKit takes that window and nothing else on the screen, as screencapture -l does for a still. It stops
// after <seconds>, or sooner once <out.mov>.stop appears: a scene with clicks in it can't tell its length beforehand.
// With cursor, the pointer shows, so the clicks can be seen.
import AppKit
import ScreenCaptureKit

// A command-line tool has no window server session until AppKit makes one.
_ = NSApplication.shared

let seconds = Double(CommandLine.arguments[1])!
let out = URL(fileURLWithPath: CommandLine.arguments[2])
let stop = CommandLine.arguments[2] + ".stop"
let cursor = CommandLine.arguments.count > 3 && CommandLine.arguments[3] == "cursor"
try? FileManager.default.removeItem(at: out)
try? FileManager.default.removeItem(atPath: stop)

Task {
  do {
    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
    guard let window = content.windows.first(where: { ($0.title ?? "").hasPrefix("Minecraft ") && ($0.title ?? "").contains("26.") }) else {
      print("no game window"); exit(1)
    }
    let filter = SCContentFilter(desktopIndependentWindow: window)
    let config = SCStreamConfiguration()
    config.width = Int(window.frame.width * 2)
    config.height = Int(window.frame.height * 2)
    config.minimumFrameInterval = CMTime(value: 1, timescale: 30)
    config.showsCursor = cursor
    config.colorSpaceName = CGColorSpace.sRGB
    let stream = SCStream(filter: filter, configuration: config, delegate: nil)
    let recording = SCRecordingOutputConfiguration()
    recording.outputURL = out
    recording.outputFileType = .mov
    let output = SCRecordingOutput(configuration: recording, delegate: Recorder())
    try stream.addRecordingOutput(output)
    try await stream.startCapture()
    let end = Date().addingTimeInterval(seconds)
    while Date() < end && !FileManager.default.fileExists(atPath: stop) {
      try await Task.sleep(nanoseconds: 100_000_000)
    }
    try? FileManager.default.removeItem(atPath: stop)
    try await stream.stopCapture()
    try await Task.sleep(nanoseconds: 500_000_000)
  } catch {
    print("recording failed: \(error)"); exit(1)
  }
  exit(0)
}

final class Recorder: NSObject, SCRecordingOutputDelegate {}

// ScreenCaptureKit shows its recording mark in the menu bar, which needs the app's main loop.
NSApplication.shared.setActivationPolicy(.accessory)
NSApplication.shared.run()
