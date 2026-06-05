// 臨時檔案：用來驗證 CodeRabbit 是否會自動 review。測完即刪。
// 故意留幾個可被 review 的點（除以零、未檢查空陣列、== vs ===）來看 CodeRabbit 反應。

function averagePnl(trades) {
  var total = 0;
  for (var i = 0; i < trades.length; i++) {
    total += trades[i].pnl;
  }
  return total / trades.length;
}

function isProfitable(pnl) {
  if (pnl == null) {
    return false;
  }
  return pnl > 0;
}

module.exports = { averagePnl, isProfitable };
