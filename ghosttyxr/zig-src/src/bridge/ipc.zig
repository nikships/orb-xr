//! Binary IPC framing shared by Kotlin and Zig JNI bridge.
const std = @import("std");

pub const ProtocolVersion: u8 = 1;

pub const Tag = enum(u8) {
    Write = 1,
    Read = 2,
    Keepalive = 3,
    Ack = 100,
    Data = 101,
    Error = 255,
    _,
};

pub const Header = packed struct {
    version: u8,
    tag: Tag,
    len: u32,
};

pub const HeaderSize: usize = 6;

fn decodeHeader(data: []const u8) ?Header {
    if (data.len < HeaderSize) return null;
    const len = std.mem.readInt(u32, data[2..6], .little);
    return .{
        .version = data[0],
        .tag = @enumFromInt(data[1]),
        .len = len,
    };
}

fn encodeHeader(header: Header) [HeaderSize]u8 {
    var out: [HeaderSize]u8 = undefined;
    out[0] = header.version;
    out[1] = @intFromEnum(header.tag);
    std.mem.writeInt(u32, out[2..6], header.len, .little);
    return out;
}

pub fn expectedLength(data: []const u8) ?usize {
    const header = decodeHeader(data) orelse return null;
    return HeaderSize + @as(usize, header.len);
}

pub fn parse(data: []const u8) error{InvalidFrame}!struct { header: Header, payload: []const u8 } {
    const header = decodeHeader(data) orelse return error.InvalidFrame;
    if (header.version != ProtocolVersion) return error.InvalidFrame;
    const total = HeaderSize + @as(usize, header.len);
    if (data.len != total) return error.InvalidFrame;
    return .{ .header = header, .payload = data[HeaderSize..total] };
}

pub fn appendMessage(alloc: std.mem.Allocator, list: *std.ArrayList(u8), tag: Tag, payload: []const u8) !void {
    const header: Header = .{
        .version = ProtocolVersion,
        .tag = tag,
        .len = @intCast(payload.len),
    };
    const header_bytes = encodeHeader(header);
    try list.appendSlice(alloc, &header_bytes);
    try list.appendSlice(alloc, payload);
}
