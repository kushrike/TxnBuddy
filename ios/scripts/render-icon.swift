#!/usr/bin/env swift
// Rebuilds the iOS icon assets from shared/icon.svg:
//   AppIcon-1024.png  opaque, square corners (iOS applies its own mask)
//   Logo.imageset     the SVG itself, used by LogoMark in the app
// Run from the repo root:  swift ios/scripts/render-icon.swift
import AppKit

let svgURL = URL(fileURLWithPath: "shared/icon.svg")
let assets = "ios/TxnBuddy/Assets.xcassets/"
let svg = try String(contentsOf: svgURL, encoding: .utf8)

// ponytail: strips the first rx= (the background rect's rounding); keep the background as the SVG's first <rect>.
let square = svg.replacingOccurrences(of: #"\srx="[^"]*""#, with: "", options: .regularExpression,
                                      range: svg.range(of: #"\srx="[^"]*""#, options: .regularExpression))
guard let image = NSImage(data: Data(square.utf8)) else { fatalError("could not parse \(svgURL.path)") }

// App Store icons must be opaque: RGB without alpha.
let px = 1024
let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
image.draw(in: NSRect(x: 0, y: 0, width: px, height: px))
try NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!
    .write(to: URL(fileURLWithPath: assets + "AppIcon.appiconset/AppIcon-1024.png"))

let logo = URL(fileURLWithPath: assets + "Logo.imageset")
try FileManager.default.createDirectory(at: logo, withIntermediateDirectories: true)
try? FileManager.default.removeItem(at: logo.appendingPathComponent("logo.svg"))
try FileManager.default.copyItem(at: svgURL, to: logo.appendingPathComponent("logo.svg"))
try #"""
{
  "images" : [ { "filename" : "logo.svg", "idiom" : "universal" } ],
  "info" : { "author" : "xcode", "version" : 1 },
  "properties" : { "preserves-vector-representation" : true }
}
"""#.write(to: logo.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
print("updated AppIcon-1024.png and Logo.imageset from \(svgURL.path)")
