package input;

import java.io.IOException;

public class Input {
    private Data data;

    public Input(String filePath) {
        try {
            this.data = Data.readFromTxt(filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Data getData() { return data; }


}
