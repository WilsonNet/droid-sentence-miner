const base = import.meta.env.BASE_URL;
const LEADING_SLASHES = /^\/+/u;

/** Prefix an internal path with Astro's configured base path. */
export function withBase(path = ""): string {
  const normalised = path.replace(LEADING_SLASHES, "");
  return `${base}${normalised}`;
}

export const docsUrl = withBase("docs/");
export const releasesUrl = "https://github.com/WilsonNet/droid-sentence-miner/releases/latest";
export const repoUrl = "https://github.com/WilsonNet/droid-sentence-miner";
