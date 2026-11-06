import type { Metadata } from "next";
import { QueryProvider } from "@/shared/providers/QueryProvider";
import "./globals.css";

export const metadata: Metadata = {
  title: "Weavo",
  description: "웹 데이터 통합 및 API 자동 생성 플랫폼",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body><QueryProvider>{children}</QueryProvider></body>
    </html>
  );
}
