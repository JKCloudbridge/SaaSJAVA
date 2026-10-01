import Link from "next/link";

export default function NotFoundPage() {
  return (
    <section className="card">
      <h1>Page not found</h1>
      <p>
        There is nothing at this address. Go back to the <Link href="/">home page</Link>.
      </p>
    </section>
  );
}
