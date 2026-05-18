export type MoodAccent = {
  hue: number;
  saturation: number;
  lightness: number;
  label: string;
};

const PALETTE: Record<string, MoodAccent> = {
  calm:        { hue: 200, saturation: 45, lightness: 62, label: "calm" },
  peaceful:    { hue: 180, saturation: 38, lightness: 60, label: "peaceful" },
  serene:      { hue: 190, saturation: 40, lightness: 65, label: "serene" },
  gentle:      { hue: 170, saturation: 35, lightness: 65, label: "gentle" },

  tense:       { hue: 8,   saturation: 70, lightness: 55, label: "tense" },
  suspense:    { hue: 350, saturation: 60, lightness: 52, label: "suspense" },
  ominous:     { hue: 0,   saturation: 50, lightness: 40, label: "ominous" },

  melancholic: { hue: 250, saturation: 32, lightness: 58, label: "melancholic" },
  sad:         { hue: 225, saturation: 28, lightness: 52, label: "sad" },
  somber:      { hue: 230, saturation: 25, lightness: 48, label: "somber" },

  joyful:      { hue: 45,  saturation: 75, lightness: 62, label: "joyful" },
  happy:       { hue: 48,  saturation: 70, lightness: 62, label: "happy" },
  bright:      { hue: 55,  saturation: 65, lightness: 65, label: "bright" },

  mysterious:  { hue: 280, saturation: 50, lightness: 55, label: "mysterious" },
  dark:        { hue: 270, saturation: 30, lightness: 38, label: "dark" },

  epic:        { hue: 28,  saturation: 75, lightness: 56, label: "epic" },
  heroic:      { hue: 35,  saturation: 78, lightness: 58, label: "heroic" },
  triumphant:  { hue: 40,  saturation: 80, lightness: 60, label: "triumphant" },

  romantic:    { hue: 340, saturation: 55, lightness: 65, label: "romantic" },
  tender:      { hue: 330, saturation: 45, lightness: 70, label: "tender" },

  neutral:     { hue: 38,  saturation: 22, lightness: 58, label: "neutral" },
};

const DEFAULT: MoodAccent = PALETTE.neutral;

export function moodAccent(mood: string | undefined | null): MoodAccent {
  if (!mood) return DEFAULT;
  const key = mood.toLowerCase().trim();
  if (PALETTE[key]) return PALETTE[key];
  for (const k of Object.keys(PALETTE)) {
    if (key.includes(k)) return PALETTE[k];
  }
  return DEFAULT;
}

export function moodToCss(accent: MoodAccent): Record<string, string> {
  return {
    "--accent-h": String(accent.hue),
    "--accent-s": `${accent.saturation}%`,
    "--accent-l": `${accent.lightness}%`,
  };
}
