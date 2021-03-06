package meghanada.session.subscribe;

import com.google.common.eventbus.Subscribe;
import meghanada.session.Session;
import meghanada.session.SessionEventBus;
import meghanada.utils.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class ParseEventSubscriber extends AbstractSubscriber {

    private static final Logger log = LogManager.getLogger(ParseEventSubscriber.class);

    public ParseEventSubscriber(final SessionEventBus sessionEventBus) {
        super(sessionEventBus);
        log.debug("subscribe source parser");
    }

    private static void parseFile(final Session session, final File file) throws IOException, ExecutionException {
        final boolean b = session.parseFile(file.getCanonicalPath());
    }

    @Subscribe
    public synchronized void on(final SessionEventBus.ParseRequest request) {

        final Session session = super.sessionEventBus.getSession();
        final File file = request.getFile();
        if (!FileUtils.isJavaFile(file)) {
            return;
        }
        try {
            ParseEventSubscriber.parseFile(session, file);
        } catch (Exception e) {
            log.warn("parse error {}", e.getMessage());
        }
    }

    @Subscribe
    public synchronized void on(SessionEventBus.ParseFilesRequest request) {
        final Session session = super.sessionEventBus.getSession();
        final List<File> files = request.getFiles();
        for (final File file : files) {
            if (!FileUtils.isJavaFile(file)) {
                continue;
            }
            try {
                ParseEventSubscriber.parseFile(session, file);
            } catch (Exception e) {
                log.warn("parse error {}", e.getMessage());
            }
        }

    }
}
