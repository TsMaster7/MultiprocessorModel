package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */

//параметры моделирования 
public class ModelingParameters 
{    
    //длительность моделирования в тактах
    public long tactsCount;
    //срднее отклонение адреса запроса к памяти от предыдущего значения
    public int querySigma;   
    //вероятность генерации запроса к памяти в очередной такт процессора
    public double queryProbability;
    //вероятность генерации запроса на чтение
    public double readQueryProbability;   
    //длительность цикла основной памяти (в тактах процессора)
    public int memoryCycleTime;
    //длительность поиска указателя в кэш-памяти с одним входом
    public int searchCachePointerTime;
    //длительность операции чтения (записи) для кэш-памяти
    public int bufferOperationTime;
    //коэффициент задержки шины
    public int systemBusDelay;
    //вероятность обращения к разделяемому блоку
    public double ShBlockRefProb;
    //число разделяемых блоков
    public int ShBlocksCount;
    
    public ModelingParameters(long tactsCount, int querySigma,
            double queryProbability, double readQueryProbability,
            int memoryCycleTime, int searchCachePointerTime, int bufferOperationTime,
            int systemBusDelay, double ShBlockRefProb, int ShBlocksCount)
    {
        this.tactsCount = tactsCount;
        this.querySigma = querySigma;
        this.queryProbability = queryProbability;
        this.readQueryProbability = readQueryProbability;
        this.memoryCycleTime = memoryCycleTime;
        this.searchCachePointerTime = searchCachePointerTime;
        this.bufferOperationTime = bufferOperationTime;
        this.systemBusDelay = systemBusDelay;
        this.ShBlockRefProb = ShBlockRefProb;
        this.ShBlocksCount = ShBlocksCount;
    }
}
