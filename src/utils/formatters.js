export const formatTWD = (num) => `NT$${Math.round(Number(num || 0)).toLocaleString()}`;

export const formatLocal = (num, symbol) => `${symbol}${Math.floor(Number(num || 0)).toLocaleString()}`;
