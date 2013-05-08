package crawl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;

public class CrawlUtil {

	public static String getPageEncodingFromHeader(HttpResponse res) {
		String returnEncode = null;
		String encoding = res.getHeaders("Content-Type")[0].getValue();// 获取网页编码格式
		int charIndex =encoding.indexOf("charset=");
		if(charIndex>0){
			returnEncode = StringUtils.substring(encoding, charIndex+8);
		}
		return returnEncode;
	}
	
	public static String getPageEncodingFromMeta(String line){
		String encoding = null;
		String regex = "<meta.*?charset=(.*?)[\";].*>";
		Pattern p =Pattern.compile(regex);
		Matcher m =p.matcher(line);
		if(m.find()){
			encoding = m.group(1);
		}
		return encoding;
	}
}
