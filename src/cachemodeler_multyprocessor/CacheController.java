package cachemodeler_multyprocessor;

import javax.swing.JTextArea;

/**
 *
 * @author Taras
 */
public class CacheController implements Runnable
{
    //текстовое поле для вывода отчета
    public JTextArea reportText;
    //номер контроллера
    public int number;
    //поток моделирования
    private Thread cacheControllerThread;
    //процессор, связанный с кэш-памятью
    private Processor processor;
    //ссылка на системную шину (для выставления запроса в очередь)
    private SystemBus systemBus;
    //параметры моделируемой системы
    private SystemParameters systemParameters;
    //параметры моделирования
    private ModelingParameters modelingParameters;
    //кэш-память
    private CacheMemory cacheMemory;
    //текущий запрос процессора для обработки
    private Query procQuery;
    //счетчик кэш-попаданий
    private int hitCounter;
    //счетчик кэш-промахов
    private int missCounter;
    //признак потребности приостановки работы контроллера
    private boolean isStoped;
            
    
    public CacheController(SystemParameters systemParameters, 
            ModelingParameters modelingParameters, JTextArea text)
    {
        this.systemParameters = systemParameters;
        this.modelingParameters = modelingParameters;
        cacheControllerThread = new Thread(this, "cache");            
        cacheMemory = new CacheMemory(systemParameters);
        hitCounter = missCounter = 0;
        this.reportText = text;
        isStoped = true;
    }
    
    public synchronized void restartController()
    {
        this.isStoped = false;
    }
    
    public synchronized CacheMemory getCacheMemory()
    {
        return this.cacheMemory;
    }
    
    public synchronized Processor getProcessor()
    {
        return this.processor;
    }
    
    public void setQuery(Query newQuery)
    {
        procQuery = newQuery;
    }
    
    //получение потока моделирования контроллера кэш-памяти
    public synchronized Thread getCacheControllerThread()
    {
        return cacheControllerThread;
    }
        
    public void addHit()
    {
        this.hitCounter++;
    }
    
    public void addMiss()
    {
        this.missCounter++;
    }
    
    public void setSystemBus(SystemBus systemBus)
    {
        this.systemBus = systemBus;
    }
    
    //установить связанный процессор
    public void setProcessor(Processor processor)
    {
        this.processor = processor;
        cacheControllerThread.setName("cache_" + processor.name);
        this.number = processor.number;
    }
    
    //запускает связанный процессор
    public void restartProcessor()
    {
        processor.restart();
    }
        
    @Override
    public void run()
    {
        while (processor.getProcessorThread().isAlive()) 
        {             
            try
            {                
                //если процессор не запущен, берем запрос и обрабатываем его
                //многопроцессорная версия !!!
                if (!processor.getRun() && !isStoped)
                {
                    //сначала ищем данные в кэше
                    //определяем номера блока и модуля
                    BlockAddress ba = cacheMemory.searchBlockPlace(procQuery.address);                          
                    //ищем тэг (определяем есть ли блок в кэше)
                    if (cacheMemory.searchBlock(ba) != Const.NOT_FOUND)    
                    //блок найден в кэше, кэш - попадание
                    {
                        //увеличиваем счетчик кэш-попаданий
                        addHit();
                        if (procQuery.isRead)   
                        //кэш-попадание при чтении 
                        {
                            //устанавливаем тип запроса 
                            procQuery.queryType = QueryType.READ_HIT;                            
                        }
                        else    
                        //кэш-попадание при записи
                        {
                            //устанавливаем тип запроса 
                            procQuery.queryType = QueryType.WRITE_HIT;                            
                        }
                        //добавляем запрос в очередь
                        //приостанавливаем работу контроллера                                                  
                        isStoped = true;
                        this.systemBus.addQuery(procQuery);                 
                    }    //конец ветки кэш-попадания  
                    else   
                    //данных нет в кэш-памяти - кэш-промах
                    {                        
                        //увеличиваем счетчик промахов
                        addMiss();
                        //определяем тип запроса и выставляем его на шину
                        if (procQuery.isRead)
                        {
                            procQuery.queryType = QueryType.READ_MISS;
                        }
                        else
                        {
                            procQuery.queryType = QueryType.WRITE_MISS;
                        }
                        //приостанавливаем работу контроллера                                                  
                        isStoped = true;
                        this.systemBus.addQuery(procQuery);                  
                    }       //конец ветки кэш-промаха (и кэш попадания)
                    
                //произведено чтение из кэша или шина возобновила поток контроллера
                //после окончания обслуживания запускаем процессор
                //reportText.append("Контроллер " + cacheControllerThread.getName() + 
                //        " выполнил запрос\n");
                //restartProcessor();     
                }   //конец текущего такта работы контроллера
                else
                {
                    cacheControllerThread.sleep(10);
                }
            }        
            catch (Exception ex)
            {
                reportText.append("------- !!! " + ex + " !!!\n");
                reportText.append("-------Сбой работы кэш-контроллера!!!\n");
            }
        }
    }
    
    public void start()
    {
        isStoped = false;
        cacheControllerThread.start();
    }  
    
    public double getAvrMemoryCycleTime()
    {        
        return (double)processor.getUnloadCount() / processor.getQueryCounter();
    }
    
    public double getHitRate()
    {
        return (double)hitCounter / (hitCounter + missCounter) * 100;        
    }        
}
