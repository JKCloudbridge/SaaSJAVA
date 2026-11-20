"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { chooseView, rememberView, useStoredView, viewFromAddress, type HomeView } from "./homeView";
import { Option1Home } from "./option1/Option1Home";
import { Option2Home } from "./option2/Option2Home";

/**
 * The Home page of Management Core (MC3, ADR-0262; two layouts since ADR-0266): a two-choice control at the top right, and
 * the layout chosen. Option 1 is the plant board, Option 2 is the dashboard that was built first. Each is a folder of its
 * own: to remove one, delete its folder, its import and its line below, and its entry in the list of choices.
 */
const OPTIONS: readonly { view: HomeView; label: string }[] = [
  { view: "1", label: "Option 1" },
  { view: "2", label: "Option 2" },
];

export function ManagementHomePage() {
  return (
    <Suspense fallback={null}>
      <HomeChoice />
    </Suspense>
  );
}

function HomeChoice() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname() ?? "";
  const stored = useStoredView();
  const view = chooseView(viewFromAddress(params?.get("view")), stored);

  const choose = (next: HomeView) => {
    rememberView(next);
    if (params?.has("view")) {
      // The address wins while it names a view, so a click takes the name out of the address.
      const rest = new URLSearchParams(params.toString());
      rest.delete("view");
      const text = rest.toString();
      router.replace(text ? `${pathname}?${text}` : pathname, { scroll: false });
    }
  };

  return (
    <>
      <div className="view-switch-row">
        <div className="view-switch" role="group" aria-label="Home layout" data-testid="view-switch">
          {OPTIONS.map((option) => (
            <button key={option.view} type="button" aria-pressed={view === option.view} onClick={() => choose(option.view)}>
              {option.label}
            </button>
          ))}
        </div>
      </div>
      {view === "1" ? <Option1Home /> : <Option2Home />}
    </>
  );
}
