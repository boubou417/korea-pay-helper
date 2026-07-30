module.exports = {
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
    "./public/index.html"
  ],

  safelist: [
  {
    pattern: /(bg|text|border)-(red|yellow|gray)-(50|200|300|500|700|800|900)/
  },
  {
    pattern: /(bg|text)-(green|blue|purple|pink)-(400|500|600)/
  },
  {
    pattern: /(from|to)-(blue|indigo|green|purple|pink)-(400|500|600)/
  },
  {
    pattern: /bg-gradient-to-r/
  }
  ],
  
  theme: {
    extend: {},
  },
  plugins: [],
}