package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */

import java.util.*;
import javax.swing.JTextArea;

//одно процессорное ядро
public class Processor implements Runnable
{
    //текстовая область для вывода отчета
    JTextArea reportText;
    //имя процессора
    public String name;    
    //номер процессора
    public int number;
    //поток, моделирующий работу процессора 
    private Thread processorThread;    
    //параметры моделирования
    private ModelingParameters modelingParameters;
    //параметры системы
    private SystemParameters systemParameters;
    //состояние процессора - true - работает, false - остановлен
    private boolean isRun;
    //адрес предыдущего запроса к памяти
    private long lastQueryAddress;
    //адрес самого старшего байта в памяти
    private long maximumAddress;
    //кэш-контроллер
    private CacheController cacheController;
    //номер текущего такта моделирования
    public long tactNumber;
        
    //счетчик запросов к памяти
    private long queryCounter;    
    //счетчик полезных тактов
    private long loadCount;
    //счетчик тактов простоя
    private long unloadCount;
    
    //получение числа полезных тактов
    public synchronized long getLoadCount()
    {
        return loadCount;
    }
    
    //получение числа холостых тактов
    public synchronized long getUnloadCount()
    {
        return unloadCount;
    }
    
    //увеличение счетчика полезных тактов
    public synchronized void addLoad(int add)
    {
        loadCount += add;
    }
    
    //увеличение счетчика холостых тактов
    public synchronized void addUnload(int add)
    {
        unloadCount += add;
    }
    
    //увеличение числа запросов к памяти
    public synchronized void addQueryCounter()
    {
        queryCounter++;
    }
    
    //получение числа запросов к памяти
    public synchronized long getQueryCounter()
    {
        return queryCounter;
    }
    
    //получение потока моделирования процессора
    public synchronized Thread getProcessorThread()
    {
        return processorThread;
    }    
    
    synchronized public boolean getRun()
    {
        return isRun;
    }
    
    synchronized public void setRun(boolean isRun)
    {
        this.isRun = isRun;
    }
    
    public void setController(CacheController cacheController)
    {
        this.cacheController = cacheController;
    }
    
    public Processor(ModelingParameters modelingParameters, SystemParameters systemParameters,
            long startAddress, long maximumAddress, String name, JTextArea text, int number)
    {
        this.modelingParameters = modelingParameters;
        this.systemParameters = systemParameters;
        this.lastQueryAddress = startAddress;
        this.maximumAddress = maximumAddress;
        this.name = name;
        this.number = number;
        this.reportText = text;
        processorThread = new Thread(this, name);     
        isRun = false;        
        queryCounter = loadCount = unloadCount = tactNumber = 0;               
    }     
  
    //генерация запроса к памяти
    private Query generateQuery()
    {       
        Random r = new Random();
        long address = 0;
        //генерация адреса запроса
        if (r.nextDouble() <= modelingParameters.ShBlockRefProb)
        //генерация адреса запроса к разделяемому блоку
        //как случайной величины в диапазоне разделяемых адресов
        {
            int sharedAreaSize = systemParameters.cacheBlockSize * modelingParameters.ShBlocksCount;
            address = r.nextInt(sharedAreaSize) + systemParameters.memorySize - sharedAreaSize;
        }
        else
        //генерация адреса запрса к частному блоку как случайной величины с нормальным распределением
        //математическое ожидание - lastQueryAddress
        //среднее квадратичное отклонение - querySigma
        {
            address = Math.round(lastQueryAddress + modelingParameters.querySigma*r.nextGaussian());            
            if (address > maximumAddress) address = maximumAddress;
            if (address < 0) address = 0;
            lastQueryAddress = address;
        }        
                
        if (r.nextDouble() <= modelingParameters.readQueryProbability)
        {
            //с вероятностью readQueryProbability генерируем запрос на чтение 
            return new Query(address, true, number);
        }
        else
        {
            //с обратной вероятностью генерируем запрос на запись
            return new Query(address, false, number);
        }
    }    
    
    //выполнить такт процессора (если он не ждет ответ из памяти)
    //с вероятностью queryProbability генерирует запрос к памяти и приостанавливает
    //работу процессора, иначе не далает ничего (продолжает работу)
    public void doNextTact(long i)
    {
        //в любом случае увеличичваем счетчик полезных тактов
        addLoad(1);
        if (new Random().nextDouble() <= modelingParameters.queryProbability)  
        {
            Query newQuery = generateQuery();
            addQueryCounter();
            String queryKind = newQuery.isRead ? "чтение" : "запись";
            reportText.append("Процессор " + name + " (Такт " + i + "): cгенерирован запрос к памяти\n");
            reportText.append("   адрес: " + newQuery.address + " " + queryKind + "\n");
            cacheController.setQuery(newQuery);
            setRun(false);                                    
        } 
        else
        {
            reportText.append("Процессор " + name + " (Такт " + i + "): операция без запроса\n");             
        }
    }   
    
    //запуск потока, моделирующего работу процессора
    synchronized public void start()
    {
        setRun(true);
        processorThread.start();
    }
    
    synchronized public void restart()
    {
        setRun(true);
        reportText.append("Контроллер " + cacheController.getCacheControllerThread().getName()
                + " выполнил запрос\n");                                  
        this.cacheController.restartController();
    }
    
    @Override
    public void run()
    {
        for (int i=0; i<modelingParameters.tactsCount; i++) 
        {            
            try
            {
                tactNumber++;
                if (!getRun())     //холостой такт (простой)
                {                    
                    processorThread.sleep(25);
                    continue;
                }
                
                doNextTact(i);
               
                if (i == modelingParameters.tactsCount-1)
                {
                    reportText.append("-----Процессор " + name + " завершил работу!!!\n");                    
                }
                processorThread.sleep(25);
            }
            catch (Exception ex)
            {
                this.processorThread.stop();
                reportText.append("-------Процессор остановлен\n");                    
            }
        }
    }    
}
