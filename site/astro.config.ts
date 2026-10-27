import sitemap from "@astrojs/sitemap";
import starlight from "@astrojs/starlight";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "astro/config";

const site = "https://wilsonnet.github.io";
const base = "/droid-sentence-miner";
const repo = "https://github.com/WilsonNet/droid-sentence-miner";

export default defineConfig({
  base,
  integrations: [
    starlight({
      customCss: ["./src/styles/starlight.css"],
      description:
        "Documentation for Droid Sentence Miner, the Android sentence miner for Japanese immersion: OCR, Yomitan lookups, sentence audio and Anki mining.",
      editLink: {
        baseUrl: `${repo}/edit/main/site/`,
      },
      favicon: "/favicon.svg",
      head: [
        {
          attrs: {
            content: `${site}${base}/og.png`,
            property: "og:image",
          },
          tag: "meta",
        },
        {
          attrs: {
            content: "1200",
            property: "og:image:width",
          },
          tag: "meta",
        },
        {
          attrs: {
            content: "630",
            property: "og:image:height",
          },
          tag: "meta",
        },
        {
          attrs: {
            content: "summary_large_image",
            name: "twitter:card",
          },
          tag: "meta",
        },
        {
          attrs: {
            content: "#1b735d",
            name: "theme-color",
          },
          tag: "meta",
        },
        {
          attrs: {
            href: `${base}/site.webmanifest`,
            rel: "manifest",
          },
          tag: "link",
        },
        {
          attrs: {
            href: `${base}/apple-touch-icon.png`,
            rel: "apple-touch-icon",
          },
          tag: "link",
        },
      ],
      lastUpdated: true,
      logo: {
        alt: "Droid Sentence Miner",
        src: "./src/assets/logo.svg",
      },
      sidebar: [
        {
          items: [
            { label: "Overview", slug: "docs" },
            { label: "Installation", slug: "docs/getting-started/installation" },
            { label: "First card", slug: "docs/getting-started/first-card" },
          ],
          label: "Start here",
        },
        {
          items: [
            { label: "Auto OCR", slug: "docs/guides/auto-ocr" },
            { label: "Screen dictionary", slug: "docs/guides/screen-dictionary" },
            { label: "Manual scan and bubble", slug: "docs/guides/manual-scan" },
            { label: "Books and manga", slug: "docs/guides/books" },
            { label: "Live text window", slug: "docs/guides/live-text" },
            { label: "Texts and sentence audio", slug: "docs/guides/texts-and-audio" },
            { label: "Anki mining", slug: "docs/guides/anki" },
          ],
          label: "Guides",
        },
        {
          items: [
            { label: "Settings", slug: "docs/reference/settings" },
            { label: "How it works", slug: "docs/reference/how-it-works" },
            { label: "Troubleshooting", slug: "docs/reference/troubleshooting" },
            { label: "Privacy", slug: "docs/reference/privacy" },
            { label: "Credits", slug: "docs/reference/credits" },
          ],
          label: "Reference",
        },
        {
          items: [
            { label: "Build from source", slug: "docs/development/building" },
            { label: "OCR benchmarks", slug: "docs/development/ocr-benchmarks" },
            { label: "Website", slug: "docs/development/website" },
          ],
          label: "Development",
        },
        {
          label: "Main site",
          link: "https://wilsonnet.github.io/droid-sentence-miner/",
        },
      ],
      social: [{ href: repo, icon: "github", label: "GitHub" }],
      tableOfContents: { maxHeadingLevel: 4, minHeadingLevel: 2 },
      title: "Droid Sentence Miner",
    }),
    sitemap({
      filter: (page) => !(page.endsWith("/404/") || page.endsWith("/404")),
    }),
  ],
  prefetch: {
    defaultStrategy: "hover",
    prefetchAll: true,
  },
  site,
  trailingSlash: "always",
  vite: {
    plugins: [tailwindcss()],
  },
});
