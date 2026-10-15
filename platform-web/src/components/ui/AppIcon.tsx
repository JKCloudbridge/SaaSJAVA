import { Icon, isIconName } from "./Icon";

/**
 * The picture of an application: the icon its key names when the browser can draw it, else the first letter of its name
 * in the same rounded square, so every application has a mark and none needs a file. The key is data an organization
 * chose; an unknown one is never an error.
 */
export function AppIcon({ iconKey, label }: { iconKey?: string | null; label: string }) {
  return (
    <span className="app-icon" aria-hidden="true">
      {isIconName(iconKey) ? <Icon name={iconKey} className="app-icon-glyph" /> : label.trim().charAt(0).toUpperCase()}
    </span>
  );
}
