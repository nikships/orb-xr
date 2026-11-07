export type OrbArchetype = "spiral" | "nebula" | "core" | "deep";

export interface OrbPalette {
  anchor: string;
  accents: [string, string, string];
}

export interface FeatureItem {
  id: string;
  title: string;
  description: string;
  benefits: string[];
}

export type RichSpan = string | { hl: string };
export type RichText = RichSpan[];

export interface SteeringClaudePane {
  win: string;
  task: string;
  status: string;
  done: boolean;
  foot: string;
}

export interface SteeringCodexPane {
  win: string;
  model: string;
  dir: string;
  promptTask: string;
  bullet: RichText;
  plan: string[];
  steer: string;
}

export interface SteeringGeminiPane {
  task: string;
  model: string;
  bullet: RichText;
  tool: { name: string; desc: string };
  code: string;
  status: string;
}

export interface SteeringScenario {
  id: string;
  label: string;
  claude: SteeringClaudePane;
  codex: SteeringCodexPane;
  gemini: SteeringGeminiPane;
}

export interface SuiteCard {
  title: string;
  description: string;
  price: string;
  icon: "speech" | "transcribe" | "type" | "clone" | "translate";
}

export interface FooterGroup {
  title: string;
  links: Array<{
    label: string;
    href: string;
  }>;
}
