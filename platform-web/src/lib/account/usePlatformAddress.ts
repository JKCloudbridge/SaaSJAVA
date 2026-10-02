"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api/client";

/** Where the platform's own pages (sign-up, reset, creating an organization) live, as seen from this address. */
export interface PlatformAddress {
  /** True on the platform host, false on an organization host, undefined until the API has answered. */
  isPlatformHost: boolean | undefined;
  /** The origin of the platform host: this page's own on the platform host, the parent domain on an organization's. */
  origin: string;
}

/**
 * Asks the API which organization this address belongs to, and works out where the platform host is: on an
 * organization host the platform host is the same address without the organization's first label. The answer only
 * decides which links to show; the backend refuses these flows on the wrong host anyway.
 */
export function usePlatformAddress(): PlatformAddress {
  const [address, setAddress] = useState<PlatformAddress>({ isPlatformHost: undefined, origin: "" });

  useEffect(() => {
    let cancelled = false;
    async function load() {
      const here = window.location;
      try {
        const { data, response } = await api.GET("/api/v1/tenant/current");
        if (cancelled) {
          return;
        }
        if (data) {
          const prefix = `${data.data.slug}.`;
          const host = here.host.startsWith(prefix) ? here.host.slice(prefix.length) : here.host;
          setAddress({ isPlatformHost: false, origin: `${here.protocol}//${host}` });
        } else {
          setAddress({ isPlatformHost: response.status === 404 ? true : undefined, origin: here.origin });
        }
      } catch {
        if (!cancelled) {
          setAddress({ isPlatformHost: undefined, origin: here.origin });
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  return address;
}
