package com.weiweihome.util

/**
  * Description：使用自带的substring截取字符串 按照字符截取，该方法按照字节截取
  *
  * author:haixin
  * Version:1.0
  * create date:2019/9/22 下午8:36
  * update date:
  */
object SubString {

  /**
    *
    * @param str 需要截取的字符串
    * @param start 字节开始的位置
    * @param end 字节结束的位置
    * @return
    */
  def getBytesSubString(str:String,start:Int,end:Int)={
    var endpoint = end
    val strlen = str.length
    val charlen = str.getBytes("GBK").length
    val strbuff = new StringBuffer()
    if (charlen < start){
      str
    }else{
      for (index <- 0 to strlen -1 ){
        var char = str.charAt(index)
        strbuff.append(char)
        if (isChineseChar(char)){
          endpoint -= 1
        }
      }
    }
    strbuff.toString.substring(start,endpoint)

  }


  def isChineseChar(c:Char)={
    val ischar = String.valueOf(c).getBytes("GBK").length >1
    ischar
  }

  def main(args: Array[String]): Unit = {
    val str = getBytesSubString("12快乐工作123,快乐学习1323213",2,21)
    println(str)
  }

}
