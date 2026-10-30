package app.platform.integration.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Which addresses an organization may point a connection at (ADR-0208): public, globally routable unicast addresses
 * only. Everything that reaches the platform's own network, a neighbour or the instance's metadata service is refused,
 * together with the ranges set aside for documentation and for translation schemes that can wrap a private address.
 * Pure: no lookups.
 */
final class HostAddresses {

    private HostAddresses() {
    }

    /** Whether the platform may connect to the address. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return publicV4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return publicV6(address.getAddress());
        }
        return false;
    }

    private static boolean publicV4(byte[] a) {
        int first = a[0] & 0xff;
        int second = a[1] & 0xff;
        int third = a[2] & 0xff;
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false; // shared address space of carriers
        }
        if (first == 169 && second == 254) {
            return false; // link local, which includes the metadata address of cloud instances
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192) {
            if (second == 168 || (second == 0 && (third == 0 || third == 2)) || (second == 88 && third == 99)) {
                return false;
            }
        }
        if (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) {
            return false;
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean publicV6(byte[] a) {
        int first = a[0] & 0xff;
        // Only global unicast, 2000::/3. That leaves out loopback, unspecified, unique local (fc00::/7), link local,
        // site local, multicast, the discard prefix and the translation prefix 64:ff9b::/96.
        if ((first & 0xe0) != 0x20) {
            return false;
        }
        int second = a[1] & 0xff;
        int third = a[2] & 0xff;
        int fourth = a[3] & 0xff;
        if (first == 0x20 && second == 0x01) {
            // 2001::/32 tunnelling, 2001:db8::/32 documentation.
            return !((third == 0 && fourth == 0) || (third == 0x0d && fourth == 0xb8));
        }
        if (first == 0x20 && second == 0x02) {
            return false; // 6to4 wraps an IPv4 address of anybody's choosing
        }
        return !(first == 0x3f && second == 0xff); // documentation, 3fff::/20
    }
}
