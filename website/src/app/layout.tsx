import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";

const geistMono = localFont({
  variable: "--font-geist-mono",
  display: "swap",
  src: "../../public/fonts/GeistMono-Variable.woff2",
});

// ci-deploy-canary: website hosting pipeline (auth pin fix)
export const metadata: Metadata = {
  title: "Orb XR — orchestrate coding agents in XR",
  description:
    "Supervise a fleet of CLI coding agents from inside XR. Floating SSH terminals on Galaxy XR, running on your Mac.",
  icons: {
    icon: "/seo/favicon.ico",
    apple: "/seo/apple-icon.png",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistMono.variable} dark h-full antialiased`}
    >
      <body className="min-h-full bg-background text-foreground">{children}</body>
    </html>
  );
}
