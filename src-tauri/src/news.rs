use crate::error::Result;
use serde::{Deserialize, Serialize};
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct NewsItem {
    pub title: String,
    pub url: String,
    #[serde(default)]
    pub date: String,
    #[serde(default)]
    pub excerpt: String,
    #[serde(default)]
    pub image: String,
    #[serde(default)]
    pub tag: String,
}

fn strip_tags(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut depth = 0i32;
    for c in s.chars() {
        match c {
            '<' => depth += 1,
            '>' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    out.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#8217;", "'")
        .replace("&#039;", "'")
        .replace("&nbsp;", " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn between<'a>(hay: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let s = hay.find(open)? + open.len();
    let e = hay[s..].find(close)? + s;
    Some(&hay[s..e])
}

fn unwrap_cdata(s: &str) -> String {
    let t = s.trim();
    t.strip_prefix("<![CDATA[")
        .and_then(|r| r.strip_suffix("]]>"))
        .unwrap_or(t)
        .trim()
        .to_string()
}

fn parse_feed(xml: &str, limit: usize) -> Vec<NewsItem> {
    let (item_open, item_close) = if xml.contains("<item>") {
        ("<item>", "</item>")
    } else {
        ("<entry>", "</entry>")
    };

    let mut out = Vec::new();
    let mut rest = xml;
    while out.len() < limit {
        let Some(start) = rest.find(item_open) else {
            break;
        };
        let block_start = start + item_open.len();
        let Some(rel_end) = rest[block_start..].find(item_close) else {
            break;
        };
        let block = &rest[block_start..block_start + rel_end];
        rest = &rest[block_start + rel_end..];

        let title = between(block, "<title>", "</title>")
            .map(unwrap_cdata)
            .unwrap_or_default();

        let url = between(block, "<link>", "</link>")
            .map(unwrap_cdata)
            .filter(|s| !s.is_empty())
            .or_else(|| {
                let at = block.find("<link")?;
                let href = between(&block[at..], "href=\"", "\"")?;
                Some(href.to_string())
            })
            .unwrap_or_default();

        let date = between(block, "<pubDate>", "</pubDate>")
            .or_else(|| between(block, "<updated>", "</updated>"))
            .or_else(|| between(block, "<published>", "</published>"))
            .map(unwrap_cdata)
            .unwrap_or_default();

        let excerpt = between(block, "<description>", "</description>")
            .or_else(|| between(block, "<summary>", "</summary>"))
            .map(unwrap_cdata)
            .map(|s| strip_tags(&s))
            .unwrap_or_default();

        let image = between(block, "<media:content url=\"", "\"")
            .or_else(|| between(block, "<enclosure url=\"", "\""))
            .map(|s| s.to_string())
            .or_else(|| between(block, "<img src=\"", "\"").map(|s| s.to_string()))
            .or_else(|| {
                let content = between(block, "<content:encoded>", "</content:encoded>")?;
                between(content, "img src=\"", "\"").map(|s| s.to_string())
            })
            .unwrap_or_default();

        if title.is_empty() && url.is_empty() {
            continue;
        }
        out.push(NewsItem {
            title,
            url,
            date,
            excerpt: excerpt.chars().take(220).collect(),
            image,
            tag: String::new(),
        });
    }
    out
}

pub async fn fetch(url: &str, limit: usize) -> Result<Vec<NewsItem>> {
    if url.trim().is_empty() {
        return Ok(Vec::new());
    }

    let text = if url.starts_with("http://") || url.starts_with("https://") {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(12))
            .user_agent("ProjectLifeZoidLauncher/0.1")
            .build()
            .map_err(|e| crate::error::Error::Other(format!("http client: {e}")))?;
        client.get(url).send().await?.text().await?
    } else {
        let path = url
            .strip_prefix("file:///")
            .unwrap_or(url)
            .replace('/', "\\");
        std::fs::read_to_string(path)?
    };
    let trimmed = text.trim_start();

    if trimmed.starts_with('[') || trimmed.starts_with('{') {
        if let Ok(items) = serde_json::from_str::<Vec<NewsItem>>(trimmed) {
            return Ok(items.into_iter().take(limit).collect());
        }
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(trimmed) {
            for key in ["items", "posts", "news", "entries"] {
                if let Some(arr) = v.get(key) {
                    if let Ok(items) = serde_json::from_value::<Vec<NewsItem>>(arr.clone()) {
                        return Ok(items.into_iter().take(limit).collect());
                    }
                }
            }
        }
        return Ok(Vec::new());
    }

    Ok(parse_feed(&text, limit))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_rss_items() {
        let xml = r#"<rss><channel>
<item><title>Patch 3 is live</title><link>https://plz.example/p3</link>
<pubDate>Mon, 18 Aug 2026 10:00:00 GMT</pubDate>
<description><![CDATA[<p>New <b>economy</b> tuning.</p>]]></description>
<media:content url="https://plz.example/a.jpg" /></item>
<item><title>Second</title><link>https://plz.example/2</link></item>
</channel></rss>"#;
        let items = parse_feed(xml, 5);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].title, "Patch 3 is live");
        assert_eq!(items[0].url, "https://plz.example/p3");
        assert_eq!(items[0].excerpt, "New economy tuning.");
        assert_eq!(items[0].image, "https://plz.example/a.jpg");
    }

    #[test]
    fn parses_atom_entries_with_href_links() {
        let xml = r#"<feed><entry><title>Atom post</title>
<link href="https://plz.example/atom"/><updated>2026-08-18T10:00:00Z</updated>
<summary>Short summary</summary></entry></feed>"#;
        let items = parse_feed(xml, 5);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].url, "https://plz.example/atom");
        assert_eq!(items[0].excerpt, "Short summary");
    }

    #[test]
    fn strips_markup_and_entities() {
        assert_eq!(strip_tags("<p>a &amp; b</p>"), "a & b");
    }
}
