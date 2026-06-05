const { describe, it, expect } = require("vitest");
const {
  averagePnl,
  isProfitable,
} = require("../coderabbit-smoke-test");

// ---------------------------------------------------------------------------
// averagePnl
// ---------------------------------------------------------------------------
describe("averagePnl", () => {
  describe("normal cases", () => {
    it("returns the correct average for a single trade", () => {
      const trades = [{ pnl: 100 }];
      expect(averagePnl(trades)).toBe(100);
    });

    it("returns the correct average for multiple trades with positive pnl", () => {
      const trades = [{ pnl: 100 }, { pnl: 200 }, { pnl: 300 }];
      expect(averagePnl(trades)).toBe(200);
    });

    it("returns the correct average when all trades have negative pnl", () => {
      const trades = [{ pnl: -50 }, { pnl: -150 }];
      expect(averagePnl(trades)).toBe(-100);
    });

    it("returns the correct average for a mix of positive and negative pnl", () => {
      const trades = [{ pnl: 200 }, { pnl: -100 }, { pnl: 0 }];
      // (200 + -100 + 0) / 3 = 100 / 3 ≈ 33.333...
      expect(averagePnl(trades)).toBeCloseTo(33.333, 2);
    });

    it("returns 0 when all pnl values are 0", () => {
      const trades = [{ pnl: 0 }, { pnl: 0 }, { pnl: 0 }];
      expect(averagePnl(trades)).toBe(0);
    });

    it("handles fractional pnl values correctly", () => {
      const trades = [{ pnl: 1.5 }, { pnl: 2.5 }];
      expect(averagePnl(trades)).toBe(2);
    });

    it("handles large pnl values without overflow", () => {
      const trades = [{ pnl: 1e15 }, { pnl: 1e15 }];
      expect(averagePnl(trades)).toBe(1e15);
    });
  });

  describe("edge cases / known bugs", () => {
    it("returns NaN for an empty trades array (division by zero bug)", () => {
      // This exposes the known bug: dividing by zero when trades is empty.
      const result = averagePnl([]);
      expect(isNaN(result)).toBe(true);
    });

    it("ignores extra properties on trade objects and only uses pnl", () => {
      const trades = [
        { pnl: 50, fee: 5, symbol: "BTCUSDT" },
        { pnl: 150, fee: 10, symbol: "ETHUSDT" },
      ];
      expect(averagePnl(trades)).toBe(100);
    });

    it("treats undefined pnl as NaN and propagates it through the sum", () => {
      const trades = [{ pnl: 100 }, { pnl: undefined }];
      expect(isNaN(averagePnl(trades))).toBe(true);
    });
  });

  describe("boundary / regression cases", () => {
    it("returns the single element itself for a one-element array", () => {
      expect(averagePnl([{ pnl: -999 }])).toBe(-999);
    });

    it("returns 0 average when positive and negative pnl cancel out", () => {
      const trades = [{ pnl: 500 }, { pnl: -500 }];
      expect(averagePnl(trades)).toBe(0);
    });

    it("handles a very large number of trades correctly", () => {
      // 1000 trades each with pnl = 42 → average should be 42
      const trades = Array.from({ length: 1000 }, () => ({ pnl: 42 }));
      expect(averagePnl(trades)).toBe(42);
    });
  });
});

// ---------------------------------------------------------------------------
// isProfitable
// ---------------------------------------------------------------------------
describe("isProfitable", () => {
  describe("profitable cases", () => {
    it("returns true for a positive integer pnl", () => {
      expect(isProfitable(1)).toBe(true);
    });

    it("returns true for a large positive pnl", () => {
      expect(isProfitable(100000)).toBe(true);
    });

    it("returns true for a small positive fractional pnl", () => {
      expect(isProfitable(0.001)).toBe(true);
    });
  });

  describe("non-profitable cases", () => {
    it("returns false for a negative pnl", () => {
      expect(isProfitable(-1)).toBe(false);
    });

    it("returns false for pnl exactly equal to 0", () => {
      expect(isProfitable(0)).toBe(false);
    });

    it("returns false for a large negative pnl", () => {
      expect(isProfitable(-100000)).toBe(false);
    });
  });

  describe("null / undefined handling (== null loose equality)", () => {
    it("returns false for null pnl", () => {
      expect(isProfitable(null)).toBe(false);
    });

    it("returns false for undefined pnl (== null catches undefined too)", () => {
      // The function uses == null, which is true for both null and undefined.
      expect(isProfitable(undefined)).toBe(false);
    });
  });

  describe("boundary / edge cases", () => {
    it("returns false for NaN pnl (NaN > 0 is false)", () => {
      expect(isProfitable(NaN)).toBe(false);
    });

    it("returns true for Infinity pnl", () => {
      expect(isProfitable(Infinity)).toBe(true);
    });

    it("returns false for -Infinity pnl", () => {
      expect(isProfitable(-Infinity)).toBe(false);
    });

    it("returns false for string '0' — no type coercion guard (regression)", () => {
      // With == null, a string '0' is NOT null/undefined, so it falls through
      // to pnl > 0. '0' > 0 is false in JS, so this returns false.
      expect(isProfitable("0")).toBe(false);
    });

    it("returns true for string '1' — implicit coercion through > operator", () => {
      // '1' > 0 coerces to true. Documents the loose-typing behavior.
      expect(isProfitable("1")).toBe(true);
    });
  });
});
