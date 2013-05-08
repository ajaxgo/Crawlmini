package crawl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.FileWriterWithEncoding;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

public class Crawl implements Runnable{
	
	public static Log log=  LogFactory.getLog(Crawl.class);

	public Set<String> visitedSet = new ConcurrentSkipListSet<String>();

	public Map<String, List<String>> allowedMap = new ConcurrentHashMap<String, List<String>>();
	
	public Queue<String> urlQueue = new ConcurrentLinkedQueue<String>();

	/**
	 * 
	 * @param url
	 * @return
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public String getContent(URL url) throws ClientProtocolException,
			IOException {
		HttpClient client = new DefaultHttpClient();
		HttpGet httpGet = new HttpGet(url.toString());
		HttpResponse response = client.execute(httpGet);
		log.info("getContent:"+url.toString());
		StringBuffer strBuf = new StringBuffer();
		if (HttpStatus.SC_OK == response.getStatusLine().getStatusCode()) {
			HttpEntity entity = response.getEntity();
			if (entity != null) {
				String encoding = null;
				if(StringUtils.isEmpty(encoding)){
					encoding = CrawlUtil.getPageEncodingFromHeader(response);
				}
//				String encoding = response.getHeaders("Content-Type")[0]
//						.getValue();// 获取网页编码格式
//				encoding = StringUtils.substring(encoding,
//						encoding.lastIndexOf("charset=") + 8);
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(entity.getContent()));
				String line = null;
				if (entity.getContentLength() > 0) {
					strBuf = new StringBuffer((int) entity.getContentLength());
					while ((line = reader.readLine()) != null) {
						if(StringUtils.isEmpty(encoding)){
							encoding = CrawlUtil.getPageEncodingFromMeta(line);
						}
						strBuf.append(line);
					}
				}
			}
		}
		// 将url标记为已访问
		markUrlAsVisited(url);
		return strBuf.toString();
	}

	/**
	 * @param url
	 * @return true为未访问过的url，幷加入到集合中
	 * false为已在集合中
	 */
	public boolean markUrlAsVisited(URL url) {
		if (!visitedSet.contains(url.toString())) {
			visitedSet.add(url.toString());
			return true;
		}
		return false;
	}

	// 处理外部链接
	public void extractHttpUrls(Map urlMap, String text) {
		Pattern httpRegexp = Pattern.compile("<[aA].*?http://.*?>.*?</[aA]>");
		Matcher m = httpRegexp.matcher(text);
		while (m.find()) {
			String url = m.group();
			String[] terms = url.split("a href=\"");
			for (String term : terms) {
				if (term.startsWith("http")) {
					int index = term.indexOf("\"");
					if (index > 0) {
						term = term.substring(0, index);
					}
					try {
						if (isAllowed(new URL(term))) {
							urlMap.put(term, term);
						}
					} catch (MalformedURLException e) {
						e.printStackTrace();
					}
//					log.info("Hyperlink: " + term);
				}
			}
		}
	}

	// 处理内部链接
	public void extractRelativeUrls(Map urlMap, String text, URL crawlerUrl) {
		Pattern relativeRegexp = Pattern
				.compile("<[aA].*?http://(.*?)\">.*?</[aA]>");
		text = StringUtils.substring(text, 10000,12000);
		Matcher m = relativeRegexp.matcher(text);
		URL textURL = crawlerUrl;
		String host = textURL.getHost();
		while (m.find()) {
			String url = m.group();
			String[] terms = url.split("<a href=\"");
			for (String term : terms) {
				if (term.startsWith("/")) {
					int index = term.indexOf("\"");
					if (index > 0) {
						term = term.substring(0, index);
					}
					String s = "http://" + host + term;
					urlMap.put(s, s);
					log.info("Relative url: " + s);
				}
			}
		}
	}

	public void addRobots(URL url) {

		String host = url.getHost();
		try {
			URL robotUrl = new URL("http://" + host + "/robots.txt");
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					robotUrl.openStream()));
			List<String> disallowedList = allowedMap.get(host.toLowerCase());
			if (disallowedList == null) {
				disallowedList = new ArrayList<String>();
			} else {
				return;
			}
			while (reader.ready()) {
				String line = reader.readLine();
				if (line != null && line.indexOf("Disallow:") == 0) {
					String disallowPath = line.substring("Disallow:".length());
					int commentIndex = disallowPath.indexOf("#");
					if (commentIndex > 0)
						disallowPath = disallowPath.substring(0, commentIndex);
					if ("/".equals(disallowPath))
						continue;
					disallowedList.add(disallowPath);
				}
			}
			allowedMap.put(host, disallowedList);
		} catch (IOException e) {
			log.error("读取robot文件错误",e);
			return;
		}

	}

	public boolean isAllowed(URL url) {
		String host = url.getHost();
		String file = url.getFile();
		List<String> disAllowed = allowedMap.get(host);
		if(CollectionUtils.isEmpty(disAllowed)){
			return true;
		}
		for (String item : disAllowed) {
			if (file.startsWith(item.trim())) {
				return false;
			}
		}
		return true;
	}

	public void saveContent(URL url, String content) {
		log.info("saveContent:"+url.toString());
		String fileStr = url.getFile();
		if (StringUtils.isEmpty(fileStr)) {
			fileStr = "index.html";
		}
		FileWriterWithEncoding fw = null;
		File file = new File("/home/tsw/workspace/mvn-project/data/" + fileStr);
		if (!file.exists()) {
			try {
				FileUtils.touch(file);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			fw = new FileWriterWithEncoding(file, "gb2312");
			fw.append(content);
		} catch (IOException e) {
			log.error("保存网页错误",e);
		} finally {
			try {
				if (fw != null) {
					fw.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}
	
	public void addQueue(Map<String,String> map){
		if(MapUtils.isEmpty(map)){
			return;
		}
		for(String item : map.keySet()){
			if(visitedSet.contains(item)){
				continue;
			}else{
				urlQueue.add(item);
			}
		}
	}
	
	public void run(){
		while(true){
			if(urlQueue.isEmpty()){
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			String url = urlQueue.poll();
			if(url==null)continue;
			URL urlObj = null;
			try {
				urlObj = new URL(url);
			} catch (MalformedURLException e) {
				continue;
			}
			if(markUrlAsVisited(urlObj)&&isSameWeb(urlObj)){
				try {
					String content = getContent(urlObj);
					Map urlMap = new HashMap();
					extractHttpUrls(urlMap, content);
					addQueue(urlMap);
					if(isRelevantURL(content)){
						saveContent(urlObj, content);
					}
					
				} catch (ClientProtocolException e) {
					continue;
				} catch (IOException e) {//可能因为无法读取内容
					log.error("无法读取"+url+"内容", e);
					urlQueue.add(url);
					continue;
				}
			}
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
			}
		}
	}
	
	/**
	 * 判断网页中得内容是否是我需要得
	 * @param content
	 * @return
	 */
	public boolean isRelevantURL(String content){
		boolean relevantFlag = false;
		String regex = ".*?科华生物.*?科华生物.*";
		Pattern p = Pattern.compile(regex);
		Matcher match = p.matcher(content);
		if(match.find()){
			relevantFlag=true;
		}
		return relevantFlag;
	}
	
	/**
	 * 判断链接是否为我爬取得网站内链接
	 * @param url
	 * @return
	 */
	public boolean isSameWeb(URL url){
		String host = url.getHost();
		Pattern p = Pattern.compile(".*?jrj.com.cn");
		Matcher m =  p.matcher(host);
		return m.matches();
	}
	
	public void init(){
		urlQueue.add("http://stock.jrj.com.cn");
	}
	
	/**程序入口
	 * @param args
	 */
	public static void main(String[] args) {
		ExecutorService service = Executors.newFixedThreadPool(5);
		Crawl obj = new Crawl();
		obj.init();
		service.execute(obj);
		service.execute(obj);
		service.execute(obj);
//		ApplicationContext contenxt = new 
	}
}
