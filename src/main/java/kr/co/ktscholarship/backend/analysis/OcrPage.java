package kr.co.ktscholarship.backend.analysis;

import java.util.List;

/** OCR이 읽은 한 페이지. 단어마다 위치(픽셀)와 확신도(0~1)를 가진다 */
public record OcrPage(int width, int height, List<Word> words) {

    public record Word(String text, int x0, int y0, int x1, int y1, double confidence) {

        public double centerY() {
            return (y0 + y1) / 2.0;
        }

        public int height() {
            return y1 - y0;
        }
    }
}
