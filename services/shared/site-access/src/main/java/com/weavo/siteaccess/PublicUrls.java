package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.INVALID_URL;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import okhttp3.Dns;
import okhttp3.HttpUrl;

final class PublicUrls {
  private PublicUrls() {}

  static HttpUrl parse(String value) {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      if (scheme == null
          || !List.of("http", "https").contains(scheme.toLowerCase(Locale.ROOT))
          || uri.getRawAuthority() == null
          || uri.getRawAuthority().contains("@")) {
        throw invalid();
      }
      HttpUrl url = HttpUrl.get(uri).newBuilder().fragment(null).build();
      String host = url.host().replaceFirst("\\.$", "");
      if (host.equals("localhost") || host.endsWith(".localhost")) {
        throw invalid();
      }
      checkLiteral(host);
      return url;
    } catch (IllegalArgumentException | NullPointerException error) {
      throw invalid();
    }
  }

  static HttpUrl redirect(HttpUrl from, String location) {
    if (location == null || location.isBlank()) {
      throw invalid();
    }
    try {
      URI reference = URI.create(location);
      if (reference.getRawAuthority() != null && reference.getRawAuthority().contains("@")) {
        throw invalid();
      }
      HttpUrl resolved = from.resolve(location);
      if (resolved == null) {
        throw invalid();
      }
      return parse(resolved.toString());
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  static List<InetAddress> resolvePublic(String host, Dns resolver) throws UnknownHostException {
    List<InetAddress> addresses = resolver.lookup(host);
    if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) {
      throw new UnsafeHost();
    }
    return addresses;
  }

  private static void checkLiteral(String host) {
    try {
      if (host.contains(":")) {
        if (!isPublic(InetAddress.getByName(host))) {
          throw invalid();
        }
      } else if (host.matches("[0-9.]+")) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
          throw invalid();
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
          int value = Integer.parseInt(parts[index]);
          if (value > 255 || !parts[index].equals(Integer.toString(value))) {
            throw invalid();
          }
          bytes[index] = (byte) value;
        }
        if (!isPublic(InetAddress.getByAddress(bytes))) {
          throw invalid();
        }
      }
    } catch (UnknownHostException | NumberFormatException error) {
      throw invalid();
    }
  }

  static final class UnsafeHost extends UnknownHostException {
    UnsafeHost() {
      super("The host did not resolve exclusively to public addresses");
    }
  }

  private static boolean isPublic(InetAddress address) {
    byte[] bytes = address.getAddress();
    int first = Byte.toUnsignedInt(bytes[0]);
    int second = Byte.toUnsignedInt(bytes[1]);
    int third = Byte.toUnsignedInt(bytes[2]);
    if (bytes.length == 4) {
      return first != 0
          && first != 10
          && first != 127
          && first < 224
          && !(first == 100 && second >= 64 && second <= 127)
          && !(first == 169 && second == 254)
          && !(first == 172 && second >= 16 && second <= 31)
          && !(first == 192
              && (second == 168
                  || second == 0 && (third == 0 || third == 2)
                  || second == 88 && third == 99))
          && !(first == 198 && (second == 18 || second == 19 || second == 51 && third == 100))
          && !(first == 203 && second == 0 && third == 113)
          && !(first == 168 && second == 63 && third == 129 && bytes[3] == 16);
    }
    return (first & 0xe0) == 0x20
        && !(first == 0x20 && second == 0x02)
        && !(first == 0x20 && second == 0x01 && third < 0x02)
        && !(first == 0x20 && second == 0x01 && third == 0x0d && (bytes[3] & 0xff) == 0xb8)
        && !(first == 0x3f && second == 0xff && (third & 0xf0) == 0);
  }

  private static SiteAccessException invalid() {
    return new SiteAccessException(INVALID_URL, "A public HTTP or HTTPS target is required");
  }
}
