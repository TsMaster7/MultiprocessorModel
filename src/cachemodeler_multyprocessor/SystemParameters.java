package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */

//параметры мультипроцессора
public class SystemParameters 
{
    //число процессоров
    public int processorsCount;
    //объем памяти
    public long memorySize;
    //размер кэш-памяти
    public int cacheSize;
    //число уровней кэш-памяти
    public int cacheLevelsCount;
    //размер строки кэш-памяти
    public int cacheBlockSize;
    //число входов кэш-памяти (алгоритм отображения)
    public int waysCount;        
    //используемый протокол когерентности
    public CoherenceProtocols coherenceProtocol;
    
    public SystemParameters(int processorsCount, long memorySize, int cacheSize,
            int cacheLevelsCount, int cacheBlockSize, int waysCount, 
            CoherenceProtocols coherenceProtocol)
    {
        this.processorsCount = processorsCount;
        this.memorySize = memorySize;
        this.cacheSize = cacheSize;
        this.cacheLevelsCount = cacheLevelsCount;
        this.cacheBlockSize = cacheBlockSize;
        this.waysCount = waysCount;
        this.coherenceProtocol = coherenceProtocol;  
    }
}
