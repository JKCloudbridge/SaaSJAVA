export default function Index() {
  return (
    <main className="spike-page">
      <h1>Sheet grid spike (FA0)</h1>
      <ul>
        {["baseline", "own", "fortune", "univer", "jspreadsheet"].map((k) => (
          <li key={k}><a href={`/${k}?rows=20&cols=8`}>{k}</a></li>
        ))}
      </ul>
    </main>
  );
}
