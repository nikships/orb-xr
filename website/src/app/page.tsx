import { BentoGrid } from "@/components/BentoGrid";
import { FeatureRail } from "@/components/FeatureRail";
import { Footer } from "@/components/Footer";
import { HardwareStrip } from "@/components/HardwareStrip";
import { Header } from "@/components/Header";
import { Hero } from "@/components/Hero";
import { ScrollCinema } from "@/components/ScrollCinema";
import { SmoothScroll } from "@/components/SmoothScroll";
import { VoiceCta } from "@/components/VoiceCta";
import { VoiceSuite } from "@/components/VoiceSuite";

export default function Home() {
  return (
    <>
      <SmoothScroll />
      <Header />
      <main>
        <Hero />
        <ScrollCinema />
        <FeatureRail />
        <BentoGrid />
        <HardwareStrip />
        <VoiceCta />
        <VoiceSuite />
      </main>
      <Footer />
    </>
  );
}
