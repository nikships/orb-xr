import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

enum QRCodeError: LocalizedError {
    case payloadEncodingFailed
    case filterOutputMissing
    case rasterizationFailed

    var errorDescription: String? {
        switch self {
        case .payloadEncodingFailed: return "Could not UTF-8 encode the pairing payload."
        case .filterOutputMissing: return "CIQRCodeGenerator produced no output image."
        case .rasterizationFailed: return "Failed to rasterize the QR CIImage to an NSImage."
        }
    }
}

enum QRCodeService {
    /// `correctionLevel` per Apple docs: "L" 7%, "M" 15%, "Q" 25%, "H" 30%.
    /// `targetSize` is the desired pixel side length; we scale via integer multiples to keep modules crisp.
    static func generate(payload: String, targetSize: CGFloat = 512, correctionLevel: String = "M") throws -> NSImage {
        guard let data = payload.data(using: .utf8) else { throw QRCodeError.payloadEncodingFailed }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = correctionLevel
        guard let output = filter.outputImage else { throw QRCodeError.filterOutputMissing }

        let scale = max(1, targetSize / output.extent.width)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        let context = CIContext(options: nil)
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else {
            throw QRCodeError.rasterizationFailed
        }
        let size = NSSize(width: scaled.extent.width, height: scaled.extent.height)
        return NSImage(cgImage: cg, size: size)
    }
}
