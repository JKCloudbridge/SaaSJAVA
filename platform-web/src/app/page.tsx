import { PlatformStatusCard } from "@/components/PlatformStatusCard";

export default function HomePage() {
  return (
    <>
      <h1>Welcome</h1>
      <p>
        This is the web frontend of the platform. It shows what the platform API tells it; the API decides everything
        else. The panel below asks the API for its status.
      </p>
      <PlatformStatusCard />
    </>
  );
}
