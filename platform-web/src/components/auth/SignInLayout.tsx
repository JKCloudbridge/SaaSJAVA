import Image from "next/image";
import type { ReactNode } from "react";
import { PLATFORM_BRAND_NAME } from "@/lib/brand";

/**
 * The shape of every sign-in screen (FA6D): two halves side by side, the form centred in the left one and the product's
 * mark centred on a soft panel in the right one. Below about 48 rem the mark goes above the form. The two screens that
 * sign a person in (the welcome page for a signed-out visitor, and the `/sign-in` address) share it, on the platform's
 * address and on an organization's alike, so they cannot drift apart. It draws what it is given and decides nothing.
 *
 * <p>The mark is the file `public/way-logo.svg` (an animated vector, the light version of the logo; the animation stops for people who ask for less motion); the panel's colour is the token `--color-brand-panel`, redefined for dark mode.
 * The product's name stays the constant in `lib/brand.ts`.
 */
export function SignInLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="sign-in-split" data-testid="sign-in-split">
      <div className="sign-in-form-half" data-testid="sign-in-form-half">
        <div className="sign-in-form-inner">
          <h1>{title}</h1>
          {children}
        </div>
      </div>
      <div className="sign-in-brand-half" data-testid="sign-in-brand-half">
        {/* A fixed vector file served as it is: the image optimizer adds nothing for it. */}
        <Image
          src="/way-logo.svg"
          alt={`${PLATFORM_BRAND_NAME} logo`}
          width={740}
          height={744}
          className="sign-in-logo"
          unoptimized
          priority
        />
      </div>
    </div>
  );
}
