import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

final class WindowCaptureService: NSObject, SCStreamDelegate, SCStreamOutput {
    var onSampleBuffer: (CMSampleBuffer) -> Void = { _ in }
    var onStopped: (String) -> Void = { _ in }

    private var stream: SCStream?
    private let sampleQueue = DispatchQueue(label: "dev.ghosttyxr.window-mirror.capture")

    func start(target: MirrorTarget) async throws {
        try await stop()

        let filter: SCContentFilter
        let configuration = SCStreamConfiguration()
        switch target {
        case .window(let window):
            filter = SCContentFilter(desktopIndependentWindow: window.window)
        case .display(let display):
            filter = SCContentFilter(display: display.display, excludingWindows: [])
        }
        let pixelWidth = filter.contentRect.width * CGFloat(filter.pointPixelScale)
        let pixelHeight = filter.contentRect.height * CGFloat(filter.pointPixelScale)
        let downscale = min(1, 2560 / max(1, max(pixelWidth, pixelHeight)))
        configuration.width = max(2, Int(pixelWidth * downscale) & ~1)
        configuration.height = max(2, Int(pixelHeight * downscale) & ~1)
        // NV12 video-range: the encoder ingests 4:2:0 natively, skipping the
        // BGRA→YUV conversion VideoToolbox would otherwise do per frame.
        configuration.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 90)
        // Deliberately shallow. A deeper queue only buys the ability to hand the
        // encoder frames that are already stale, which shows up as latency rather
        // than smoothness. Dropping a frame is the cheaper failure.
        configuration.queueDepth = 3
        configuration.showsCursor = true

        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: sampleQueue)
        try await stream.startCapture()
        self.stream = stream
    }

    func stop() async throws {
        guard let stream else { return }
        self.stream = nil
        try await stream.stopCapture()
    }

    func stream(_ stoppedStream: SCStream, didStopWithError error: Error) {
        guard stoppedStream === stream else { return }
        onStopped("Window capture stopped: \(error.localizedDescription)")
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of outputType: SCStreamOutputType) {
        guard outputType == .screen, sampleBuffer.isValid else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(
            sampleBuffer,
            createIfNecessary: false
        ) as? [[SCStreamFrameInfo: Any]],
           let statusValue = attachments.first?[.status] as? Int,
           let status = SCFrameStatus(rawValue: statusValue),
           status != .complete,
           status != .started {
            return
        }
        onSampleBuffer(sampleBuffer)
    }
}
