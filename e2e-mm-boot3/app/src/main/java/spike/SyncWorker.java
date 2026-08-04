package spike;

import org.springframework.stereotype.Service;

/** Marker class covered ONLY by /sync — its presence in a per-trace .exec proves S1 attribution. */
@Service
public class SyncWorker {

    public String work(int inputCount) {
        int total = 0;
        for (int index = 0; index < inputCount; index++) {
            if (index % 2 == 0) {
                total += index;
            } else {
                total += index * 3;
            }
        }
        return "sync-total=" + total;
    }
}
