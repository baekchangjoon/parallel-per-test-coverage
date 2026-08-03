package spike;

import org.springframework.stereotype.Service;

/** Marker class covered ONLY by /call-downstream. */
@Service
public class DownstreamWorker {

    public String mark(int inputCount) {
        int total = 0;
        for (int index = 0; index < inputCount; index++) {
            total += index * 7;
        }
        return "downstream-total=" + total;
    }
}
