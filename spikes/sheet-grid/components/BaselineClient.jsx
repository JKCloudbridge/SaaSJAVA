"use client";
import { useReady } from "./Harness";

export default function BaselineClient() {
  useReady(true);
  return <main className="spike-page"><h1>Baseline: no grid</h1></main>;
}
