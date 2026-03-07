package com.wangver.hanime.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleTextNormalizerTest {

    @Test
    void repairsUtf8MojibakeWithoutBreakingEmoji() {
        String broken = "pkã¡ãã👯ââï¸âï¸ã«çè¶³ä¸ãããã¦å\u0085¨åäº¤å°¾ï¼ï¼ï¼🥕ããã«å°ºã»ä¸­åºãæãã";

        String repaired = TitleTextNormalizer.normalize(broken);

        assertEquals("pkちゃん👯‍♀️‍️に片足上げさせて全力交尾！！！🥕【フル尺・中出し有り】", repaired);
    }

    @Test
    void stripsHanimeSiteSuffixFromWatchPageTitle() {
        String broken = "OVA ã·ã¹ã¿ã¼ããªã¼ãã¼ ï¼4 [ä¸­æå­å¹] - Håæ¼«/è£çª/ç·ä¸ç - Hanime1.me";

        String repaired = TitleTextNormalizer.normalize(broken);

        assertEquals("OVA シスターブリーダー ＃4 [中文字幕]", repaired);
    }
}
