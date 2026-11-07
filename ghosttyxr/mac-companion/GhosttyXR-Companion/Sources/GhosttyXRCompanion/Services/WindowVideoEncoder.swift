import AVFoundation
import CoreMedia
import Foundation
import VideoToolbox

/// Codec the mirror encoder produces. Negotiated against the codecs the headset
/// actually has a hardware decoder for, intersected with the user preference.
enum WindowMirrorVideoCodec: String, CaseIterable {
    case h264
    case hevc

    var displayName: String {
        switch self {
        case .h264: return "H.264"
        case .hevc: return "HEVC"
        }
    }
}

/// One encoded frame, already split into Annex-B slices. The transport writes
/// and flushes each slice as it arrives so encode, transmit, and decode overlap
/// rather than running back to back.
struct EncodedWindowFrame {
    let slices: [Data]
    let ptsUs: UInt64
    let isKeyFrame: Bool
    let width: Int
    let height: Int
}

/// VideoToolbox H.264/HEVC encoder configured the way Virtual Desktop configures
/// its own: real-time, no frame reordering, no periodic IDR, and a hard
/// short-window data-rate cap so a single complex frame cannot blow the Wi-Fi
/// budget and stall the link.
final class WindowVideoEncoder {
    var onFrame: (EncodedWindowFrame) -> Void = { _ in }
    var onError: (String) -> Void = { _ in }

    private var session: VTCompressionSession?
    private var width = 0
    private var height = 0
    private var bitrate = WindowVideoEncoder.defaultBitrate
    private var codec: WindowMirrorVideoCodec = .h264
    private var forceKeyFrameRequested = false
    private let queue = DispatchQueue(label: "dev.ghosttyxr.window-mirror.encoder")
    private let admissionLock = NSLock()
    private var pendingEncodeTasks = 0

    static let defaultBitrate = 25_000_000
    static let maxBitrate = 100_000_000
    static let minBitrate = 5_000_000

    /// Frame cadence we tell rate control to expect.
    static let expectedFrameRate = 90

    // 2 = one encoding + one waiting (~11ms max queue latency at 90fps). A cap
    // of 1 would drop every frame that arrives while the previous one encodes,
    // halving throughput; unbounded lets stale frames pile up behind a slow
    // encode and replay as lag.
    private static let maxPendingEncodeTasks = 2

    /// Hard ceiling window, in seconds, for `DataRateLimits`. Short windows are
    /// what actually bound queueing delay — this is the VideoToolbox analogue of
    /// x264's `vbv-maxrate` / `vbv-bufsize` pair.
    private static let dataRateWindowSeconds = 0.5

    /// Bytes-per-window allowance, scaled slightly above the average target so
    /// normal frame-size variance is not clipped.
    private static let dataRateBurstFactor = 1.5

    /// Target average bitrate in bits/sec, clamped to 5–100 Mbps.
    ///
    /// Applied to the **live** session with `VTSessionSetProperty`. This is the
    /// whole point of the design: adaptation never tears down the encoder, never
    /// renegotiates, and never forces an IDR storm, so it is invisible.
    func setBitrate(_ bitsPerSecond: Int) {
        let clamped = min(max(bitsPerSecond, Self.minBitrate), Self.maxBitrate)
        queue.async {
            guard self.bitrate != clamped else { return }
            self.bitrate = clamped
            guard let session = self.session else { return }
            self.applyRateControl(to: session, bitrate: clamped)
        }
    }

    /// Select the codec for the stream. A change tears down the live session so
    /// the next frame reconfigures; this only happens on an explicit user
    /// setting change, never as an automatic reaction to network conditions.
    func setCodec(_ newCodec: WindowMirrorVideoCodec) {
        queue.async {
            guard self.codec != newCodec else { return }
            self.codec = newCodec
            self.teardownSession()
        }
    }

    var activeCodec: WindowMirrorVideoCodec {
        queue.sync { codec }
    }

    /// Force the next encoded frame to be a keyframe. Driven by the headset's
    /// `keyFrameRequest`: because the GOP is effectively infinite, this is the
    /// only thing that produces an IDR after the first one.
    func forceKeyFrame() {
        queue.async {
            self.forceKeyFrameRequested = true
        }
    }

    func encode(_ sampleBuffer: CMSampleBuffer) {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        admissionLock.lock()
        guard pendingEncodeTasks < Self.maxPendingEncodeTasks else {
            // Prefer dropping a stale frame over sending it late.
            admissionLock.unlock()
            return
        }
        pendingEncodeTasks += 1
        admissionLock.unlock()

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let frameWidth = CVPixelBufferGetWidth(imageBuffer)
        let frameHeight = CVPixelBufferGetHeight(imageBuffer)

        queue.async {
            defer {
                self.admissionLock.lock()
                self.pendingEncodeTasks -= 1
                self.admissionLock.unlock()
            }
            do {
                try self.ensureSession(width: frameWidth, height: frameHeight)
                guard let session = self.session else { return }
                var frameProperties: CFDictionary?
                if self.forceKeyFrameRequested {
                    self.forceKeyFrameRequested = false
                    frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue!] as CFDictionary
                }
                let status = VTCompressionSessionEncodeFrame(
                    session,
                    imageBuffer: imageBuffer,
                    presentationTimeStamp: pts,
                    duration: .invalid,
                    frameProperties: frameProperties,
                    sourceFrameRefcon: nil,
                    infoFlagsOut: nil
                )
                if status != noErr {
                    self.onError("VideoToolbox encode failed: \(status)")
                }
            } catch {
                self.onError(error.localizedDescription)
            }
        }
    }

    func stop() {
        queue.sync {
            teardownSession()
        }
    }

    private func teardownSession() {
        if let session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        session = nil
        width = 0
        height = 0
        forceKeyFrameRequested = false
    }

    private func ensureSession(width: Int, height: Int) throws {
        if session != nil, self.width == width, self.height == height { return }
        teardownSession()
        self.width = width
        self.height = height

        var newSession: VTCompressionSession?
        // Ask explicitly for the hardware encoder rather than hoping for it.
        let specification: [CFString: Any] = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: kCFBooleanTrue!,
        ]
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(width),
            height: Int32(height),
            codecType: codec == .hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
            encoderSpecification: specification as CFDictionary,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: WindowVideoEncoder.outputCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &newSession
        )
        guard status == noErr, let newSession else {
            throw NSError(domain: "WindowVideoEncoder", code: Int(status), userInfo: [
                NSLocalizedDescriptionKey: "Could not create \(codec.displayName) encoder: \(status)",
            ])
        }

        // Real-time: the encoder must not batch or look ahead.
        VTSessionSetProperty(newSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        // No B-frames and no reorder buffer: encode latency is exactly one frame.
        VTSessionSetProperty(newSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        // Never hold frames inside the encoder.
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_MaxFrameDelayCount,
            value: NSNumber(value: 0) as CFTypeRef
        )
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality,
            value: kCFBooleanTrue
        )
        // Power-efficiency mode trades latency for battery; a streamer wants the
        // opposite.
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_MaximizePowerEfficiency,
            value: kCFBooleanFalse
        )
        // High/Main rather than Baseline: Baseline costs real quality at the same
        // bitrate, and the headset decodes High 5.2 fine.
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_ProfileLevel,
            value: codec == .hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel
        )
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_ExpectedFrameRate,
            value: NSNumber(value: Self.expectedFrameRate) as CFTypeRef
        )
        // Effectively infinite GOP. There is no periodic keyframe, so there is no
        // recurring multi-hundred-KB spike to hitch on; recovery is driven by the
        // headset asking for an IDR when it actually detects a problem.
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_MaxKeyFrameInterval,
            value: NSNumber(value: Int32.max) as CFTypeRef
        )
        VTSessionSetProperty(
            newSession,
            key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration,
            value: NSNumber(value: 0) as CFTypeRef
        )
        applyRateControl(to: newSession, bitrate: bitrate)
        VTCompressionSessionPrepareToEncodeFrames(newSession)
        session = newSession
    }

    /// Average target plus a hard short-window ceiling. The pair is what keeps
    /// latency bounded without a pacer: the encoder itself can never emit a burst
    /// large enough to build a queue in the socket.
    private func applyRateControl(to session: VTCompressionSession, bitrate: Int) {
        VTSessionSetProperty(
            session,
            key: kVTCompressionPropertyKey_AverageBitRate,
            value: NSNumber(value: bitrate) as CFTypeRef
        )
        let windowBytes = Double(bitrate) / 8.0 * Self.dataRateWindowSeconds * Self.dataRateBurstFactor
        let limits = [
            NSNumber(value: Int(windowBytes.rounded())),
            NSNumber(value: Self.dataRateWindowSeconds),
        ] as CFArray
        VTSessionSetProperty(
            session,
            key: kVTCompressionPropertyKey_DataRateLimits,
            value: limits
        )
    }

    private func handleEncoded(sampleBuffer: CMSampleBuffer) {
        let isKeyFrame = Self.isKeyFrame(sampleBuffer)
        let slices = Self.annexBSlices(from: sampleBuffer, codec: codec, isKeyFrame: isKeyFrame)
        guard !slices.isEmpty else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let ptsUs = pts.isValid ? UInt64(max(0.0, CMTimeGetSeconds(pts) * 1_000_000)) : 0
        onFrame(
            EncodedWindowFrame(
                slices: slices,
                ptsUs: ptsUs,
                isKeyFrame: isKeyFrame,
                width: width,
                height: height
            )
        )
    }

    private static let outputCallback: VTCompressionOutputCallback = { refcon, _, status, _, sampleBuffer in
        guard status == noErr, let refcon, let sampleBuffer else { return }
        let encoder = Unmanaged<WindowVideoEncoder>.fromOpaque(refcon).takeUnretainedValue()
        encoder.handleEncoded(sampleBuffer: sampleBuffer)
    }

    private static func isKeyFrame(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(
            sampleBuffer,
            createIfNecessary: false
        ) as? [[CFString: Any]],
            let first = attachments.first
        else { return true }
        return first[kCMSampleAttachmentKey_NotSync] == nil
    }

    /// Converts the AVCC length-prefixed bitstream VideoToolbox produces into
    /// Annex-B slices, one per NAL unit.
    ///
    /// On a keyframe the parameter sets (VPS+SPS+PPS for HEVC, SPS+PPS for H.264)
    /// are prepended to the first slice, so a headset joining or resyncing
    /// mid-stream never needs an out-of-band parameter set.
    private static func annexBSlices(
        from sampleBuffer: CMSampleBuffer,
        codec: WindowMirrorVideoCodec,
        isKeyFrame: Bool
    ) -> [Data] {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return [] }
        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        let status = CMBlockBufferGetDataPointer(
            blockBuffer,
            atOffset: 0,
            lengthAtOffsetOut: nil,
            totalLengthOut: &totalLength,
            dataPointerOut: &dataPointer
        )
        guard status == noErr, let dataPointer, totalLength > 0 else { return [] }

        let format = CMSampleBufferGetFormatDescription(sampleBuffer)

        var parameterSets = Data()
        if isKeyFrame, let format {
            appendParameterSets(from: format, codec: codec, to: &parameterSets)
        }

        var nalLengthSize = 4
        if let format {
            var lengthSize: Int32 = 0
            _ = parameterSet(
                from: format,
                codec: codec,
                index: 0,
                pointerOut: nil,
                sizeOut: nil,
                countOut: nil,
                nalUnitHeaderLengthOut: &lengthSize
            )
            if lengthSize > 0 {
                nalLengthSize = Int(lengthSize)
            }
        }

        var slices: [Data] = []
        var offset = 0
        while offset + nalLengthSize <= totalLength {
            var nalLength = 0
            for i in 0..<nalLengthSize {
                nalLength = (nalLength << 8) | Int(UInt8(bitPattern: dataPointer[offset + i]))
            }
            offset += nalLengthSize
            guard nalLength > 0, offset + nalLength <= totalLength else { break }
            var slice = Data()
            if slices.isEmpty, !parameterSets.isEmpty {
                slice.append(parameterSets)
            }
            slice.appendStartCode()
            slice.append(
                contentsOf: UnsafeBufferPointer(
                    start: UnsafeRawPointer(dataPointer + offset).assumingMemoryBound(to: UInt8.self),
                    count: nalLength
                )
            )
            slices.append(slice)
            offset += nalLength
        }

        // A keyframe whose payload did not parse into NALs still has to deliver
        // its parameter sets, or the headset can never configure a decoder.
        if slices.isEmpty, !parameterSets.isEmpty {
            slices.append(parameterSets)
        }
        return slices
    }

    private static func appendParameterSets(
        from format: CMFormatDescription,
        codec: WindowMirrorVideoCodec,
        to data: inout Data
    ) {
        var setCount = 0
        _ = parameterSet(
            from: format,
            codec: codec,
            index: 0,
            pointerOut: nil,
            sizeOut: nil,
            countOut: &setCount,
            nalUnitHeaderLengthOut: nil
        )
        for index in 0..<setCount {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            let status = parameterSet(
                from: format,
                codec: codec,
                index: index,
                pointerOut: &pointer,
                sizeOut: &size,
                countOut: nil,
                nalUnitHeaderLengthOut: nil
            )
            guard status == noErr, let pointer, size > 0 else { continue }
            data.appendStartCode()
            data.append(contentsOf: UnsafeBufferPointer(start: pointer, count: size))
        }
    }

    private static func parameterSet(
        from format: CMFormatDescription,
        codec: WindowMirrorVideoCodec,
        index: Int,
        pointerOut: UnsafeMutablePointer<UnsafePointer<UInt8>?>?,
        sizeOut: UnsafeMutablePointer<Int>?,
        countOut: UnsafeMutablePointer<Int>?,
        nalUnitHeaderLengthOut: UnsafeMutablePointer<Int32>?
    ) -> OSStatus {
        switch codec {
        case .hevc:
            return CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                format,
                parameterSetIndex: index,
                parameterSetPointerOut: pointerOut,
                parameterSetSizeOut: sizeOut,
                parameterSetCountOut: countOut,
                nalUnitHeaderLengthOut: nalUnitHeaderLengthOut
            )
        case .h264:
            return CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format,
                parameterSetIndex: index,
                parameterSetPointerOut: pointerOut,
                parameterSetSizeOut: sizeOut,
                parameterSetCountOut: countOut,
                nalUnitHeaderLengthOut: nalUnitHeaderLengthOut
            )
        }
    }
}

private extension Data {
    mutating func appendStartCode() {
        append(contentsOf: [0x00, 0x00, 0x00, 0x01])
    }
}
