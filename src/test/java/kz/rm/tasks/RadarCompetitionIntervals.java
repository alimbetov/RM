package kz.rm.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Test-only monotonic clock evidence; does not change service timing or offered load. */
final class RadarCompetitionIntervals {
    private RadarCompetitionIntervals() {}
    record Span(long start,long end) {
        long nanos(){return end-start;}
    }
    static int overlapCount(List<Span> commands,List<Span> reads) {
        int command=0,count=0;
        for(var read:reads){
            while(command<commands.size() && commands.get(command).end()<=read.start())command++;
            if(command<commands.size() && commands.get(command).start()<read.end())count++;
        }
        return count;
    }
    static void write(Path report,int block,List<Span> commands,List<Span> reads)throws IOException {
        var csv=new StringBuilder("kind,sample,startNanos,endNanos\n");
        append(csv,"command",commands);append(csv,"read",reads);
        Files.writeString(report.resolve("100000-block"+block+"-intervals.csv"),csv);
    }
    private static void append(StringBuilder csv,String kind,List<Span> spans){
        for(int i=0;i<spans.size();i++){
            var span=spans.get(i);
            csv.append(kind).append(',').append(i+1).append(',').append(span.start()).append(',').append(span.end()).append('\n');
        }
    }
}
