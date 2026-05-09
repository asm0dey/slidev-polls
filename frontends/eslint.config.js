// @ts-check
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import vue from "eslint-plugin-vue";
import vueParser from "vue-eslint-parser";
import prettier from "eslint-config-prettier/flat";
import globals from "globals";
import { defineConfig } from "eslint/config";

export default defineConfig(
  {
    ignores: [
      "**/dist/**",
      "**/node_modules/**",
      "**/.bun/**",
      "**/playwright-report/**",
      "**/test-results/**"
    ]
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs["flat/recommended"],
  {
    files: ["**/*.vue"],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tseslint.parser,
        sourceType: "module",
        extraFileExtensions: [".vue"]
      }
    }
  },
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    rules: {
      "@typescript-eslint/no-unused-vars": ["warn", { argsIgnorePattern: "^_" }]
    }
  },
  // Design-system primitives in shared/ui intentionally use single-word, HTML-like names.
  {
    files: ["shared/src/ui/**/*.vue"],
    rules: {
      "vue/multi-word-component-names": "off"
    }
  },
  // Consumers re-register those primitives under their original names; HTML reserved-name
  // collisions are a non-issue because templates always reference the imported component.
  {
    rules: {
      "vue/no-reserved-component-names": "off"
    }
  },
  {
    files: ["**/*.test.ts", "**/*.spec.ts"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off"
    }
  },
  // Disable eslint rules that conflict with prettier (layout concerns).
  // Keep this last so it overrides rules from the configs above.
  prettier
);
