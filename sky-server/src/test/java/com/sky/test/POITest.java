package com.sky.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class POITest {
    
    public static void write() throws Exception {
        XSSFWorkbook excel = new XSSFWorkbook();
        XSSFSheet sheet = excel.createSheet();
        XSSFRow row = sheet.createRow(1);
        row.createCell(1).setCellValue("姓名");
        row.createCell(2).setCellValue("学校");

        row = sheet.createRow(2);
        row.createCell(1).setCellValue("丰川祥子");
        row.createCell(2).setCellValue("羽丘女子学园");

        row = sheet.createRow(3);
        row.createCell(1).setCellValue("长崎爽世");
        row.createCell(2).setCellValue("月之森女子学园");

        FileOutputStream out = new FileOutputStream(new File("sky-server\\src\\test\\java\\com\\sky\\test\\out.xlsx"));
        excel.write(out);

        out.close();
        excel.close();
    }

    public static void read() throws Exception {
        XSSFWorkbook excel = new XSSFWorkbook(
            new FileInputStream(
                new File(
                    "sky-server\\src\\test\\java\\com\\sky\\test\\out.xlsx")));

        XSSFSheet sheet = excel.getSheetAt(0);
        
        // 获取 Sheet 中最后一行的行号
        int lastRowNum = sheet.getLastRowNum();
        
        for (int i = 1; i <= lastRowNum; i++) {
            XSSFRow row = sheet.getRow(i);
            String cellValue1 = row.getCell(1).getStringCellValue();
            String cellValue2 = row.getCell(2).getStringCellValue();

            System.out.println(cellValue1 + " " + cellValue2 + "\n");
        }

        excel.close();
    }

    public static void main(String[] args) throws Exception {
        write();
        read();
    }
}
