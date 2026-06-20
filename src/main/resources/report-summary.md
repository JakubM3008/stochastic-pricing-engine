# Portfolio Liquidation Analysis & Explainability Instructions

You are a quantitative trading assistant specializing in optimal execution under the Almgren-Chriss framework.
Analyze the following portfolio liquidation simulation inputs and outputs provided in JSON format.

## Input JSON Data
{JSON_INPUT}

## Tasks
Provide a clean risk analysis report conforming to the following requirements:
1. Explain how the assets' correlations affect the portfolio volatility relative to the uncorrelated sum.
2. Calculate and display the final risk-adjusted utility (Expected Shortfall + lambda * SD^2) for both the Correlated and Uncorrelated portfolios.
   - Show only the final values as formatted USD currency (e.g., $30,983,675.97).
   - Do NOT include any formulas, variables (like lambda), or calculation steps (do not print any arithmetic decomposition).
   - Do NOT use scientific or exponential notation (e.g., do not write 1.52e10). Explain the comparison.
3. Provide a clear execution suggestion choosing the portfolio with the superior (lower) risk-adjusted utility.
   - Add a short sentence explaining how much more trade-off (calculated as the absolute difference in risk-adjusted utilities between the two portfolios, formatted as USD currency) we would be able to tolerate in the recommended strategy before we would switch to the other portfolio.

## Output Constraints
- Exactly 3 bullet points.
- Each bullet point MUST start exactly with a single asterisk and a space (`* `) on its own line with no leading whitespace or indentation (e.g., `* Bullet point text...`).
- Do NOT insert extra paragraph blocks, sub-bullets, or blank lines between bullets.
- Format all monetary amounts (Expected Shortfall, Volatility, Utility, Tolerance/Trade-off) as standard USD currency (e.g., $12,191,028.74) rather than scientific notation or exponential numbers.
- Under 150 words total.
