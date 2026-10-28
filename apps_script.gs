// Dán vào Google Sheet: Tiện ích mở rộng > Apps Script (thay toàn bộ code cũ)
function doGet(e) {
  var p = e.parameter;
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = p.sheet ? ss.getSheetByName(p.sheet) : ss.getSheets()[0];
  if (!sh) return ContentService.createTextOutput("ERR: không tìm thấy sheet");

  if (p.action == "read") {               // đọc bảng để hiển thị trong app
    var rows = Math.min(parseInt(p.rows || 60), sh.getMaxRows());
    var cols = Math.min(parseInt(p.cols || 12), sh.getMaxColumns());
    var v = sh.getRange(1, 1, rows, cols).getDisplayValues();
    return ContentService.createTextOutput(JSON.stringify({ values: v }))
      .setMimeType(ContentService.MimeType.JSON);
  }

  var row = parseInt(p.row);              // ghi 1 ô
  if (row > sh.getMaxRows()) sh.insertRowsAfter(sh.getMaxRows(), row - sh.getMaxRows());
  var cell = sh.getRange(p.col + p.row);
  cell.setNumberFormat("@");              // giữ số 0 đầu của mã vạch
  cell.setValue(p.value);
  SpreadsheetApp.flush();
  return ContentService.createTextOutput("OK");
}
