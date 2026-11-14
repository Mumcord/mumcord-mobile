// SPDX-License-Identifier: AGPL-3.0-only

/// An IPv4 (4 bytes) or IPv6 (16 bytes) address in network order.
struct IpAddress: Equatable {
    let bytes: [UInt8]

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    private var isV4: Bool { bytes.count == 4 }

    var isLoopback: Bool {
        isV4 ? bytes[0] == 127 : bytes.dropLast().allSatisfy { $0 == 0 } && bytes.last == 1
    }

    /// 169.254.0.0/16 and fe80::/10.
    var isLinkLocal: Bool {
        isV4 ? bytes[0] == 169 && bytes[1] == 254 : bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80
    }

    /// 224.0.0.0/4 and ff00::/8.
    var isMulticast: Bool {
        isV4 ? bytes[0] & 0xF0 == 0xE0 : bytes[0] == 0xFF
    }

    var isBroadcast: Bool {
        isV4 && bytes.allSatisfy { $0 == 0xFF }
    }
}

/// A network as the device sees it on one of its interfaces, for example 192.168.1.0/24.
struct IpPrefix: Equatable {
    let address: IpAddress
    let length: Int

    func contains(_ other: IpAddress) -> Bool {
        let own = address.bytes
        let bytes = other.bytes
        guard own.count == bytes.count, length <= own.count * 8 else { return false }
        let wholeBytes = length / 8
        for index in 0..<wholeBytes where own[index] != bytes[index] {
            return false
        }
        let restBits = length % 8
        if restBits == 0 { return true }
        let mask = UInt8(truncatingIfNeeded: 0xFF << (8 - restBits))
        return own[wholeBytes] & mask == bytes[wholeBytes] & mask
    }
}

/// How a server address relates to the local network privacy of iOS.
enum Locality: Equatable {
    /// On a network the device is directly connected to, or link-local, multicast or broadcast.
    /// This is what the system blocks without local network access.
    case onLink
    /// A private address that is not on a directly connected network, for example behind a
    /// second router or a VPN. By Apple's description it is reachable without the access, so
    /// nobody is told up front; it only shapes the error when such a server is unreachable.
    case privateRange
    /// Everything else: the internet, which local network access never affects.
    case other
}

/// Where a server is, from the addresses its name resolved to and the networks the device is
/// directly connected to (`onLink`). The most local address decides, because the engine may use
/// any of them.
func localityOf(addresses: [IpAddress], onLink: [IpPrefix]) -> Locality {
    if addresses.contains(where: { $0.isOnLink(onLink) }) { return .onLink }
    if addresses.contains(where: \.isPrivate) { return .privateRange }
    return .other
}

/// Names ending in `.local` are looked up on the local network itself (mDNS).
func isLocalName(_ host: String) -> Bool {
    var name = Substring(host)
    while name.hasSuffix(".") { name = name.dropLast() }
    return name.lowercased().hasSuffix(".local")
}

private extension IpAddress {
    func isOnLink(_ onLink: [IpPrefix]) -> Bool {
        !isLoopback && (isLinkLocal || isMulticast || isBroadcast || onLink.contains { $0.contains(self) })
    }

    var isPrivate: Bool {
        !isLoopback && privateRanges.contains { $0.contains(self) }
    }
}

/// Private IPv4 ranges, the shared range of carrier-grade NAT, and IPv6 unique local addresses.
private let privateRanges = [
    IpPrefix(address: IpAddress(bytes: [10, 0, 0, 0]), length: 8),
    IpPrefix(address: IpAddress(bytes: [172, 16, 0, 0]), length: 12),
    IpPrefix(address: IpAddress(bytes: [192, 168, 0, 0]), length: 16),
    IpPrefix(address: IpAddress(bytes: [100, 64, 0, 0]), length: 10),
    IpPrefix(address: IpAddress(bytes: [0xFC] + [UInt8](repeating: 0, count: 15)), length: 7),
]
