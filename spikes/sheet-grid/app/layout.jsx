import "./tokens.css";
import "./spike.css";

export const metadata = { title: "Sheet grid spike" };

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
