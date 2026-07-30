export default function analyze(amountKRW, settings, mode) {
  const rate = settings.exchangeRate;

  if (mode === "cash") {
    return {
      bestPlan: { steps: [], reward: 0 },
      noSplitBest: { steps: [], reward: 0 }
    };
  }

  const payments = settings.payments.filter(p =>
    mode === "all" ? true : p.type === "card"
  );

  function calcReward(krw, p) {
    const spend = krw * rate;

    const limit =
      p.spendLimit ||
      (p.bonusRate > 0
        ? p.bonusLimit / p.bonusRate
        : Number.MAX_SAFE_INTEGER);

    const remainSpend = Math.max(limit - p.used, 0);

    const eligible = Math.min(spend, remainSpend);

    return (
      eligible * p.baseRate +
      Math.min(eligible * p.bonusRate, p.bonusLimit)
    );
  }

  //------------------------
  // 不拆單最佳
  //------------------------

  let noSplitBest = {
    steps: [],
    reward: 0
  };

  payments.forEach(p => {
    const r = calcReward(amountKRW, p);

    if (r > noSplitBest.reward) {
      noSplitBest = {
        steps: [{ name: p.name, amount: amountKRW }],
        reward: r
      };
    }
  });

  //------------------------
  // 真正最佳拆單 V3.1
  //------------------------

  const remainUsed = {};

  payments.forEach(p => {
    remainUsed[p.name] = p.used;
  });

  let remainKRW = amountKRW;

  const steps = [];

  let reward = 0;

  while (remainKRW > 0) {

    let bestPayment = null;
    let bestRate = -1;
    let bestReward = 0;
    let bestSpendKRW = 0;

    payments.forEach(p => {

      const spendLimit =
        p.spendLimit ||
        (p.bonusRate > 0
          ? p.bonusLimit / p.bonusRate
          : Number.MAX_SAFE_INTEGER);

      const remainSpend =
        Math.max(spendLimit - remainUsed[p.name], 0);

      if (remainSpend <= 0 && p.baseRate <= 0)
        return;

      const canUseKRW =
        Math.min(remainKRW,
          Math.floor(remainSpend / rate));

      const rewardValue =
        calcReward(canUseKRW, {
          ...p,
          used: remainUsed[p.name]
        });

      const effectiveRate =
        canUseKRW > 0
          ? rewardValue / (canUseKRW * rate)
          : p.baseRate;

      if (effectiveRate > bestRate) {
        bestRate = effectiveRate;
        bestPayment = p;
        bestReward = rewardValue;
        bestSpendKRW = canUseKRW;
      }

    });

    if (!bestPayment)
      break;

    // 如果沒有加碼額度了
    if (bestSpendKRW <= 0) {

      steps.push({
        name: bestPayment.name,
        amount: remainKRW
      });

      reward +=
        remainKRW *
        rate *
        bestPayment.baseRate;

      remainKRW = 0;
      break;
    }

    steps.push({
      name: bestPayment.name,
      amount: bestSpendKRW
    });

    reward += bestReward;

    remainUsed[bestPayment.name] +=
      bestSpendKRW * rate;

    remainKRW -= bestSpendKRW;

  }

  return {
    bestPlan: {
      steps,
      reward
    },
    noSplitBest
  };
}

